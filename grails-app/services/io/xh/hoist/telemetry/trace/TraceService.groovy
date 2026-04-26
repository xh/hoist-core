/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.telemetry.trace

import groovy.transform.CompileStatic
import groovy.transform.NamedParam
import groovy.transform.NamedVariant
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.context.Context
import io.opentelemetry.context.Scope
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.ReadWriteSpan
import io.opentelemetry.sdk.trace.ReadableSpan
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.SpanProcessor
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter
import io.xh.hoist.BaseService
import io.xh.hoist.config.ConfigService
import io.xh.hoist.exception.RoutineException
import io.opentelemetry.api.common.AttributeKey
import io.xh.hoist.util.Utils
import org.springframework.boot.context.event.ApplicationFailedEvent
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.context.event.SpringApplicationEvent
import org.springframework.context.ApplicationListener

import java.time.Instant

import static io.xh.hoist.telemetry.OtelUtils.getSuppressOtlpExport
import static io.xh.hoist.cluster.ClusterService.otelResourceAttributes
import static io.xh.hoist.cluster.ClusterService.startupTime
import static io.xh.hoist.util.Utils.exceptionHandler
import static java.lang.Long.parseLong
import static java.util.concurrent.TimeUnit.MILLISECONDS

/**
 * Central service for distributed tracing in a Hoist application.
 *
 * Provides OpenTelemetry-based tracing with OTLP export, configured dynamically via the
 * {@code xhTraceConfig} soft config entry. All methods are safe to call when tracing is
 * disabled — they return no-ops with negligible overhead.
 *
 * Use {@link #withSpan} to execute a closure within a span (primary API, also on BaseService),
 * or {@link #createSpan} to start a span with manual lifecycle management (for interceptors, etc.).
 */
@CompileStatic
class TraceService extends BaseService implements ApplicationListener<SpringApplicationEvent> {

    static clearCachesConfigs = ['xhTraceConfig']

    ConfigService configService
    TraceImplService traceImplService

    private List<SpanExporter> _customExporters = []
    private OpenTelemetrySdk _otelSdk
    private SpanExporter _otlpExporter
    private Resource _resource
    private TraceConfig _config
    private final HoistSampler _sampler = new HoistSampler()
    private SpanRef _serverLoadSpan

    /** Sink for exporting spans. @internal*/
    ExportProcessor exportProcessor

    void init() {
        syncConfig()
        traceImplService.initialize()
    }


    //--------------------------------------------------
    // Public API
    //--------------------------------------------------
    /** True if tracing is enabled. */
    boolean isEnabled() {
        _otelSdk
    }

    /**
     * Current {@link OpenTelemetrySdk}, or null if tracing is disabled.
     * @internal - for framework-internal use.
     */
    OpenTelemetrySdk getOtelSdk() {
        _otelSdk
    }

    /**
     * Execute a closure within a new trace span. Main entry point.
     *
     * Creates a child span under the current context. Exceptions are recorded on the span and
     * re-thrown. See {@link #createSpan} for parameter documentation.
     *
     * The closure is always passed a non-null {@link SpanRef} — when tracing is disabled, a
     * shared no-op object is provided so callers never need to null-check.
     *
     * For combined tracing + logging + metrics, use {@link io.xh.hoist.telemetry.ObservedRun} via
     * {@link BaseService#observe()}.
     */
    <T> T withSpan(Map args, Closure<T> c) {
        SpanRef span = createSpan(args.subMap(['name', 'kind', 'tags', 'caller', 'startTime']))
        try {
            return c.maximumNumberOfParameters > 0 ? c.call(span) : c.call()
        } catch (Throwable t) {
            if (!(t instanceof RoutineException)) {
                span.setError(exceptionHandler.summaryTextForThrowable(t))
            }
            span.recordException(t)
            throw t
        } finally {
            span.close()
        }
    }

    /**
     * Create and start a new trace span, making it the current context.
     *
     * Returns a {@link SpanRef} containing the active Span and Scope. The caller
     * is responsible for calling {@link Scope#close} and {@link Span#end} when done —
     * typically in a finally block. A no-op span is returned if tracing is not enabled.
     *
     * The `xh.source` tag defaults to `'hoist'` for spans whose name starts with `'xh.'` and
     * `'app'` otherwise. Callers may override all tag values, including setting to null to prevent
     * any default tag from being applied.
     */
    @NamedVariant
    private SpanRef createSpan(
        /** Span name (e.g. 'processOrder', 'HTTP GET'). */
        @NamedParam(required = true) String name,
        /** Span kind — INTERNAL (default), SERVER for inbound requests, CLIENT for outbound calls. */
        @NamedParam SpanKind kind = SpanKind.INTERNAL,
        /** Key-value attributes to set on the span. */
        @NamedParam Map<String, ?> tags = [:],
        /** Object making the call, used to auto-set the 'code.namespace' attribute. */
        @NamedParam Object caller = null,
        /** Optional backdated start time; defaults to now. */
        @NamedParam Instant startTime = null
    ) {
        def sdk = _otelSdk
        if (!sdk) return SpanRef.NOOP

        // Build complete tag set
        // Remove nulls at end, they are used in this API to just prevent defaults
        tags = [
            'xh.source': name.startsWith('xh.') ? 'hoist' : 'app',
            'code.namespace': caller?.class?.name,
            *:hoistTags(),
            *:tags
        ] as Map<String, ?>
        tags.removeAll({it.value == null})

        def spanBuilder = sdk.getTracer('io.xh.hoist')
            .spanBuilder(name)
            .setSpanKind(kind)
        if (startTime) spanBuilder.setStartTimestamp(startTime)

        try {
            _sampler.setSampleRate(getSampleRate(name, tags))
            def span = spanBuilder.startSpan(),
                ret = new SpanRef(span, span.makeCurrent(), kind)
            ret.setTags(tags)
            return ret
        } finally {
            _sampler.clearSampleRate()
        }
    }

    //-------------
    // Misc
    //-------------
    /**
     * Register an additional {@link SpanExporter} to receive both server-generated and
     * client-relayed spans. Triggers a pipeline rebuild.
     */
    synchronized void addExporter(SpanExporter exporter) {
        _customExporters = _customExporters + [exporter]
        syncConfig()
    }

    /** Remove a previously registered {@link SpanExporter}. */
    synchronized void removeExporter(SpanExporter exporter) {
        _customExporters = _customExporters - [exporter]
        syncConfig()
    }

    //--------------------------------------------------
    // Framework-internal
    //--------------------------------------------------
    /**
     * Submit client-side spans received from the browser.
     *
     * Converts the client span JSON into OTel {@link io.opentelemetry.sdk.trace.data.SpanData}
     * objects and exports them through the configured export pipeline.
     *
     * Client spans are pre-sampled by the client -- only exportable (i.e. sampled spans)
     * are expected here.
     *
     * @param spans - list of span maps as serialized by the client}
     * @internal
     */
    void submitClientSpans(List<Map> spans) {
        def resource = _resource
        if (!resource) return
        def extraTags = hoistTags()
        spans.each {
            exportProcessor.submitSpan(new ClientSpanData(it, resource, extraTags) as ReadableSpan)
        }
    }

    /**
     * Open the outer 'xh.server.load' span, backdated to {@link io.xh.hoist.cluster.ClusterService#startupTime}.
     * Must be called from hoist-core's BootStrap thread.
     * @internal
     */
    void startServerLoadSpan() {
        _serverLoadSpan = createSpan(
            name: 'xh.server.load',
            caller: this,
            startTime: startupTime.toInstant()
        )
    }

    //----------------
    // Implementation
    //----------------
    /**
     * Cross-cutting tags stamped on every span — both server-generated
     * (via {@link ExportProcessor#onStart}) and client-relayed (via {@link ClientSpanData}).
     *
     * Note: must never generate spans itself to avoid infinitee recursion.  Keep simple.
     */
    private Map<String, ?> hoistTags() {
        def identityService = Utils.identityService,
            authUsername = identityService?.authUsername,
            username = identityService?.username
        return [
            'user.name': authUsername,
            'xh.impersonating': (authUsername && authUsername != username) ? username : null,
            'xh.isPrimary': Utils.clusterService?.isPrimary
        ]
    }

    private synchronized void syncConfig() {
        _config = new TraceConfig(configService.getMap('xhTraceConfig'))
        def otlpEnabled = _config.otlpEnabled && !suppressOtlpExport

        withDebug(['Syncing tracing pipeline', [enabled: _config.enabled, otlp: otlpEnabled]]) {
            shutdownProvider()

            if (!_config.enabled) return

            def attrsBuilder = Attributes.builder()
            otelResourceAttributes.each { k, v -> attrsBuilder.put(AttributeKey.stringKey(k), v) }
            _resource = Resource.default.merge(Resource.create(attrsBuilder.build()))

            // Built-in OTLP exporter
            if (otlpEnabled) {
                def otlpBuilder = OtlpHttpSpanExporter.builder()
                def conf = _config.otlpConfig
                if (conf.endpoint) {
                    otlpBuilder.setEndpoint(conf.endpoint as String)
                }
                if (conf.timeout) {
                    otlpBuilder.setTimeout(parseLong(conf.timeout as String), MILLISECONDS)
                }
                if (conf.headers instanceof Map) {
                    conf.headers.each { k, v ->
                        otlpBuilder.addHeader(k as String, v as String)
                    }
                }
                _otlpExporter = otlpBuilder.build()
            } else {
                _otlpExporter = null
            }

            exportProcessor = new ExportProcessor()
            def providerBuilder = SdkTracerProvider.builder()
                .setResource(_resource)
                .setSampler(_sampler)
                .addSpanProcessor(new TagSpanProcessor())
                .addSpanProcessor(exportProcessor)

            _otelSdk = OpenTelemetrySdk.builder()
                .setTracerProvider(providerBuilder.build())
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.instance))
                .build()
        }
    }

    private void shutdownProvider() {
        _otelSdk?.sdkTracerProvider?.shutdown()
        _otelSdk = null
        _resource = null
    }

    /**
     * Evaluate sampling rules against a span's name and tags. Rules match on tag keys; the
     * reserved key {@code name} is matched against the span's name (glob-capable, same as
     * tag values). Returns the sample rate from the first matching rule, or the configured
     * fallback rate if no rule matches.
     */
    private double getSampleRate(String name, Map tags) {
        try {
            def fallback = _config.sampleRate.toDouble(),
                rules = _config.sampleRules
            if (!rules) return fallback

            for (Map rule in rules) {
                Map match = rule.match as Map
                if (match?.every { k, v -> matchesValue(k == 'name' ? name : tags[k], v) } &&
                    rule.sampleRate instanceof Number
                ) {
                    return ((Number) rule.sampleRate).doubleValue()
                }
            }
            return fallback
        } catch (Exception e) {
            logError("Failed to compute sample rate", e)
            return 0d
        }
    }

    /** For strings, Simple glob matching: {@code *} = any, {@code foo*} = prefix, {@code *foo} = suffix. */
    private boolean matchesValue(Object actual, Object pattern) {
        if (!(actual instanceof String) || !(pattern instanceof String)) return actual == pattern
        def patternStr = pattern as String,
            actualStr = actual as String

        if (patternStr == '*') return true
        def startsWithWild = patternStr.startsWith('*'),
            endsWithWild = patternStr.endsWith('*'),
            core = patternStr.replaceAll('^\\*|\\*\$', '')

        if (startsWithWild && endsWithWild) return actualStr.contains(core)
        if (startsWithWild) return actualStr.endsWith(core)
        if (endsWithWild) return actualStr.startsWith(core)
        return actual == pattern
    }

    //--------------------------------------------------
    // Lifecycle
    //--------------------------------------------------
    void onApplicationEvent(SpringApplicationEvent event) {
        if (!_serverLoadSpan) return
        if (event instanceof ApplicationReadyEvent) {
            _serverLoadSpan.close()
            _serverLoadSpan = null
        }
        if (event instanceof ApplicationFailedEvent) {
            def t = event.exception
            _serverLoadSpan.recordException(t)
            _serverLoadSpan.setError(exceptionHandler.summaryTextForThrowable(t))
            _serverLoadSpan.close()
            _serverLoadSpan = null
        }
    }

    void clearCaches() {
        super.clearCaches()
        syncConfig()
    }

    void destroy() {
        shutdownProvider()
        super.destroy()
    }

    Map getAdminStats() {[
        config: configForAdminStats('xhTraceConfig')
    ]}


    //------------------------
    // Support inner classes
    //------------------------
    @CompileStatic
    class ExportProcessor implements SpanProcessor {
        List<BatchSpanProcessor> batchExporters
        ExportProcessor() {
            def exporters = _otlpExporter ? _customExporters + [_otlpExporter] : _customExporters
            batchExporters = exporters.collect { BatchSpanProcessor.builder(it).build() }
        }

        void submitSpan(ReadableSpan span) {
            onEnd(span)
        }

        boolean isStartRequired() { false }
        boolean isEndRequired() { true }
        void onStart(Context ctx, ReadWriteSpan span) {}
        void onEnd(ReadableSpan span) {
            batchExporters.each { it.onEnd(span) }
        }

        CompletableResultCode shutdown() {
            batchExporters.each { it.shutdown() }
            CompletableResultCode.ofSuccess()
        }
        CompletableResultCode forceFlush() { CompletableResultCode.ofSuccess() }
    }

    @CompileStatic
    class TagSpanProcessor implements SpanProcessor {
        boolean isStartRequired() { true }
        boolean isEndRequired() { false }
        void onStart(Context ctx, ReadWriteSpan span) {
            hoistTags().each { k, v ->
                if (v != null) span.setAttribute(k, v as String)
            }
        }
        void onEnd(ReadableSpan span) {}
        CompletableResultCode shutdown() { CompletableResultCode.ofSuccess() }
        CompletableResultCode forceFlush() { CompletableResultCode.ofSuccess() }
    }
}