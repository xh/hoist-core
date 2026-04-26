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
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor
import io.opentelemetry.sdk.trace.samplers.Sampler
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter
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
import static io.xh.hoist.telemetry.trace.TraceService.TagSpanProcessor.getDROPPED_RECURSIVE
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
    TailSamplingService tailSamplingService


    private List<SpanExporter> _customExporters = []
    private OpenTelemetrySdk _otelSdk
    private SpanExporter _otlpExporter
    private Resource _resource
    private SpanRef _serverLoadSpan
    private boolean _sampleEnabled

    /** Sink for exporting spans. @internal*/
    ExportProcessor exportProcessor


    void init() {
        // Init dependents before pipeline built — avoid recursion when their own init creates spans.
        parallelInit([tailSamplingService, traceImplService])
        syncConfig()
    }

    //--------------------------------------------------
    // Public API
    //--------------------------------------------------
    /** True if tracing is enabled. */
    boolean isEnabled() {
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

    //--------------------
    // Framework
    //--------------------
    /**
     * Current {@link OpenTelemetrySdk}, or null if tracing is disabled.
     * @internal
     */
    OpenTelemetrySdk getOtelSdk() {
        _otelSdk
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

    /**
     * Accept client-submitted spans.
     * @internal
     */
    void submitClientSpans(List<Map> rawSpans) {
        def resource = _resource
        if (!resource) return
        def extras = commonAttrs()
        rawSpans.each {
            def span = new ClientSpanData(it, resource, extras)
            _sampleEnabled ?
                tailSamplingService.submitSpan(span) :
                exportProcessor.submitSpan(span)
        }
    }

    //--------------------------------------------------
    // Implementation
    //-------------------------------------------------
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
            *:tags
        ] as Map<String, ?>
        tags.removeAll({it.value == null})

        def spanBuilder = sdk.getTracer('io.xh.hoist')
            .spanBuilder(name)
            .setSpanKind(kind)
        if (startTime) spanBuilder.setStartTimestamp(startTime)

        def span = spanBuilder.startSpan(),
            ret = new SpanRef(span, span.makeCurrent(), kind)
        ret.setTags(tags)
        return ret
    }

    /** Server-authoritative attributes stamped on every span (server and client). */
    private Map<String, Object> commonAttrs() {
        def attrs = [:] as Map<String, Object>
        def identityService = Utils.identityService,
            authUsername = identityService?.authUsername,
            username = identityService?.username
        if (authUsername) {
            attrs['user.name'] = authUsername
            if (authUsername != username) attrs['xh.impersonating'] = username
        }
        attrs['xh.isPrimary'] = Utils.clusterService.isPrimary
        return attrs
    }

    private TraceConfig getConfig() {
        new TraceConfig(configService.getMap('xhTraceConfig'))
    }

    private synchronized void syncConfig() {
        def config = getConfig()
        def otlpEnabled = config.otlpEnabled && !suppressOtlpExport

        withDebug(['Syncing tracing pipeline', [enabled: config.enabled, otlp: otlpEnabled]]) {
            shutdownProvider()

            if (!config.enabled) return

            _sampleEnabled = config.sampleEnabled
            def attrsBuilder = Attributes.builder();
            otelResourceAttributes.each { k, v -> attrsBuilder.put(AttributeKey.stringKey(k), v) }
            _resource = Resource.default.merge(Resource.create(attrsBuilder.build()))

            // Built-in OTLP exporter
            if (otlpEnabled) {
                def otlpBuilder = OtlpHttpSpanExporter.builder()
                def conf = config.otlpConfig
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
                .setSampler(Sampler.parentBased(Sampler.alwaysOn()))
                .addSpanProcessor(new TagSpanProcessor())
                .addSpanProcessor(_sampleEnabled ? tailSamplingService : exportProcessor)

            _otelSdk = OpenTelemetrySdk.builder()
                .setTracerProvider(providerBuilder.build())
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.instance))
                .build()
        }
    }

    private void shutdownProvider() {
        _otelSdk?.sdkTracerProvider?.shutdown()
        exportProcessor?.shutdown()
        exportProcessor = null
        _otelSdk = null
        _resource = null
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

    //--------------------------------------------------
    // TagSpanProcessor — stamps server-authoritative tags on every server span as it starts.
    //--------------------------------------------------
    /**
     * Local {@link SpanProcessor} that decorates each newly started server span with the common
     * server-authoritative attributes.
     *
     * Includes a re-entry guard against recursion when OTel auto-instrumentation creates spans
     * for our own tagging work (e.g., identity/cluster lookups).
     */
    @CompileStatic
    class TagSpanProcessor implements SpanProcessor {

        private static final ThreadLocal<Boolean> inOnStart = ThreadLocal.withInitial { false }
        static final AttributeKey<Boolean> DROPPED_RECURSIVE = AttributeKey.booleanKey('xh.droppedByGuard')

        boolean isStartRequired() { true }
        boolean isEndRequired() { false }

        void onStart(Context ctx, ReadWriteSpan span) {
            if (inOnStart.get()) {
                span.setAttribute(DROPPED_RECURSIVE, true)
                return
            }
            inOnStart.set(true)
            try {
                commonAttrs().each { k, v -> setAttr(span, k, v) }
            } finally {
                inOnStart.set(false)
            }
        }

        void onEnd(ReadableSpan span) {}
        CompletableResultCode shutdown() { CompletableResultCode.ofSuccess() }
        CompletableResultCode forceFlush() { CompletableResultCode.ofSuccess() }

        private static void setAttr(ReadWriteSpan span, String k, Object v) {
            if (v instanceof Boolean) span.setAttribute(k, v)
            else if (v instanceof Number) span.setAttribute(k, v.longValue())
            else span.setAttribute(k, v.toString())
        }
    }

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
            if (span.toSpanData().attributes.get(DROPPED_RECURSIVE)) return
            batchExporters.each { it.onEnd(span) }
        }
        CompletableResultCode shutdown() {
            batchExporters.each { it.shutdown() }
            CompletableResultCode.ofSuccess()
        }
        CompletableResultCode forceFlush() { CompletableResultCode.ofSuccess() }
    }
}