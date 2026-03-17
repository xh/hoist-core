/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.telemetry

import groovy.transform.CompileStatic
import groovy.transform.NamedParam
import groovy.transform.NamedVariant
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.context.Scope
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.context.propagation.TextMapGetter
import io.opentelemetry.context.propagation.TextMapSetter
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor
import io.opentelemetry.sdk.trace.samplers.Sampler
import io.opentelemetry.sdk.trace.samplers.SamplingResult
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter
import io.opentelemetry.sdk.trace.export.SpanExporter
import io.micrometer.core.instrument.Counter
import io.xh.hoist.BaseService
import io.xh.hoist.config.ConfigService
import jakarta.servlet.http.HttpServletRequest
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase

import io.opentelemetry.api.common.AttributeKey
import grails.async.Promises

import static io.xh.hoist.cluster.ClusterService.instanceName
import static io.xh.hoist.util.Utils.appCode
import static io.xh.hoist.util.Utils.appEnvironment
import static io.xh.hoist.util.Utils.appVersion
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
 *
 * For context propagation, use {@link #restoreContextFromRequest} and {@link #injectContext} for HTTP requests,
 * or {@link #captureTraceparent} / {@link #restoreContextFromTraceparent} for non-HTTP propagation
 * (e.g. cluster tasks).
 */
@CompileStatic
class TraceService extends BaseService {

    static clearCachesConfigs = ['xhTraceConfig']

    ConfigService configService
    MetricsService metricsService

    private final List<SpanExporter> _exporters = []
    private OpenTelemetrySdk _otelSdk
    private SpanExporter _otlpExporter
    private Resource _resource
    private double _sampleRate = 1.0
    private Counter _spansRequested
    private Counter _spansCreated


    void init() {
        def registry = metricsService.registry
        _spansRequested = Counter.builder('trace.spans.requested')
            .description('Total spans requested')
            .tags('source', 'infra')
            .register(registry)
        _spansCreated = Counter.builder('trace.spans.created')
            .description('Total spans created (i.e. that passed sampling')
            .tags('source', 'infra')
            .register(registry)

        installContextPropagation()
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
     * Execute a closure within a new trace span.
     *
     * Creates a child span under the current context. Exceptions are recorded on the span and
     * re-thrown. See {@link #createSpan} for parameter documentation.
     *
     * For combined tracing + logging + metrics, use {@link ObservedRun} via
     * {@link BaseService#observe()}.
     */
    <T> T withSpan(Map args, Closure<T> c) {
        SpanRef span = createSpan(args.subMap(['name', 'kind', 'tags', 'caller']))
        try {
            return c.maximumNumberOfParameters > 0 ? c.call(span) : c.call()
        } catch (Throwable t) {
            span?.recordException(t)
            throw t
        } finally {
            span?.close()
        }
    }

    /**
     * Create and start a new trace span, making it the current context.
     *
     * Returns a {@link SpanRef} containing the active Span and Scope. The caller
     * is responsible for calling {@link Scope#close} and {@link Span#end} when done —
     * typically in a finally block.
     *
     * Use this when the span lifecycle spans multiple method calls (e.g. interceptors).
     * For simpler cases where a closure defines the span boundary, prefer {@link #withSpan}.
     */
    @NamedVariant
    SpanRef createSpan(
        /** Span name (e.g. 'processOrder', 'HTTP GET'). */
        @NamedParam(required = true) String name,
        /** Span kind — INTERNAL (default), SERVER for inbound requests, CLIENT for outbound calls. */
        @NamedParam SpanKind kind = SpanKind.INTERNAL,
        /** Key-value attributes to set on the span. */
        @NamedParam Map<String, ?> tags = [:],
        /** Object making the call, used to auto-set the 'code.namespace' attribute. */
        @NamedParam Object caller = null
    ) {
        if (!enabled) return null

        def spanBuilder = _otelSdk.getTracer('io.xh.hoist').spanBuilder(name).setSpanKind(kind),
            span = spanBuilder.startSpan(),
            pending = new SpanRef(span, span.makeCurrent(), kind)

        pending.setTags(tags)
        if (!tags.source) pending.setTag('source', 'app')
        if (caller) pending.setTag('code.namespace', caller.class.name)
        pending.setTag('user', username ?: 'Anon')

        return pending
    }


    //-------------
    // Misc
    //-------------
    /**
     * Register an additional {@link SpanExporter} to receive both server-generated and
     * client-relayed spans. Triggers a pipeline rebuild.
     */
    void addExporter(SpanExporter exporter) {
        _exporters.add(exporter)
        syncConfig()
    }

    /** Remove a previously registered {@link SpanExporter}. */
    void removeExporter(SpanExporter exporter) {
        _exporters.remove(exporter)
        syncConfig()
    }


    //---------------------------
    // Context Propagation
    //---------------------------
    /**
     * Inject W3C trace context onto an outbound HTTP request.
     * No-op if tracing is disabled or no active span exists.
     */
    void injectContext(HttpUriRequestBase request) {
        if (!enabled || !Span.current().spanContext.valid) return
        _otelSdk.propagators.textMapPropagator.inject(Context.current(), request, HTTP_SETTER)
    }

    /**
     * Capture the current trace context as a W3C traceparent string.
     * Returns null if tracing is disabled or no active span exists.
     */
    String captureTraceparent() {
        if (!enabled || !Span.current().spanContext.valid) return null
        Map<String, String> carrier = [:]
        _otelSdk.propagators.textMapPropagator.inject(Context.current(), carrier, MAP_SETTER)
        carrier.traceparent
    }

    /**
     * Restore a previously captured traceparent string as the current context.
     *
     * Returns a {@link Scope} that must be closed when done (typically in a finally block),
     * or null if the traceparent is null/empty or tracing is disabled.
     */
    Scope restoreContextFromTraceparent(String traceparent) {
        if (!traceparent || !enabled) return null
        def context = _otelSdk.propagators.textMapPropagator.extract(Context.current(),  [traceparent: traceparent], MAP_GETTER)
        context.makeCurrent()
    }

    /**
     * Restore a W3C trace parent context from incoming HTTP request headers.
     *
     * Returns a {@link Scope} that must be closed when done (typically in a finally block),
     * or null if the traceparent is null/empty or tracing is disabled.
     */
    Scope restoreContextFromRequest(HttpServletRequest request) {
        if (!enabled) return null
        def context = _otelSdk.propagators.textMapPropagator.extract(Context.current(), request, HTTP_GETTER)
        context.makeCurrent()
    }


    //--------------------------------------------------
    // Framework-internal
    //--------------------------------------------------
    /** @internal - Configured span exporters for direct export of client-relayed spans. */
    List<SpanExporter> getExporters() { _exporters.asImmutable() }

    /** @internal - The OTel Resource describing this application instance. */
    Resource getTracingResource() { _resource }

    /**
     * Determine if a trace should be sampled based on its trace ID.
     *
     * Uses the same deterministic algorithm as the OTel {@code traceIdRatioBased} sampler:
     * the lower 8 bytes of the trace ID are compared against a threshold derived from the
     * configured sample rate. Re-implemented here to allow consistent sampling decisions
     * across both server-originated spans and client-relayed spans.
     *
     * @internal
     */
    boolean shouldSample(String traceId) {
        _spansRequested?.increment()
        boolean ret
        if (_sampleRate >= 1.0){
            ret = true
        } else if (_sampleRate <= 0.0) {
            ret = false
        } else {
            long lowerLong = Long.parseUnsignedLong(traceId.substring(16), 16)
            ret = Long.compareUnsigned(lowerLong, (long) (Long.MAX_VALUE * _sampleRate)) < 0
        }
        if (ret) _spansCreated?.increment()
        return ret
    }


    //--------------------------------------------------
    // Implementation
    //--------------------------------------------------
    /**
     * Install a delegating PromiseFactory that propagates OTel trace context to worker
     * threads spawned by Grails {@code task {}} calls. Installed once at startup.
     */
    private void installContextPropagation() {
        Promises.promiseFactory = new ContextPropagatingPromiseFactory(Promises.promiseFactory)
    }

    private TraceConfig getConfig() {
        new TraceConfig(configService.getMap('xhTraceConfig'))
    }

    private void syncConfig() {
        def config = getConfig()

        withDebug(['Syncing tracing pipeline', [enabled: config.enabled, otlp: config.otlpEnabled]]) {
            shutdownProvider()

            if (!config.enabled) return

            _resource = Resource.default.merge(
                Resource.create(Attributes.builder()
                    .put(stringKey('service.name'), appCode)
                    .put(stringKey('service.instance.id'), instanceName)
                    .put(stringKey('deployment.environment'), appEnvironment.toString())
                    .put(stringKey('service.version'), appVersion)
                    .build()
                )
            )

            _sampleRate = config.sampleRate

            def providerBuilder = SdkTracerProvider.builder()
                .setResource(_resource)
                .setSampler(_sampler)

            // Built-in OTLP exporter
            if (_otlpExporter) {
                _exporters.remove(_otlpExporter)
                _otlpExporter = null
            }
            if (config.otlpEnabled) {
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
                _exporters.add(_otlpExporter)
            }

            // Add all exporters
            exporters.each {
                providerBuilder.addSpanProcessor(BatchSpanProcessor.builder(it).build())
            }

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

    private AttributeKey stringKey(String str) {
        AttributeKey.stringKey(str)
    }


    //--------------------------------------------------
    // Lifecycle
    //--------------------------------------------------
    void clearCaches() {
        super.clearCaches()
        syncConfig()
    }

    void destroy() {
        shutdownProvider()
        super.destroy()
    }

    Map getAdminStats() {[
        config: configForAdminStats('xhTraceConfig'),
        spansRequested: _spansRequested?.count(),
        spansCreated: _spansCreated?.count()
    ]}

    /** Shared getter for extracting trace context from incoming servlet requests. */
    private static final TextMapGetter<HttpServletRequest> HTTP_GETTER =
        new TextMapGetter<HttpServletRequest>() {
            Iterable<String> keys(HttpServletRequest carrier) {
                Collections.list(carrier.headerNames)
            }
            String get(HttpServletRequest carrier, String key) {
                carrier.getHeader(key)
            }
        }

    /** Shared setter for injecting trace context onto outbound Apache HTTP requests. */
    private final TextMapSetter<HttpUriRequestBase> HTTP_SETTER =
        new TextMapSetter<HttpUriRequestBase>() {
            void set(HttpUriRequestBase carrier, String key, String value) {
                carrier?.setHeader(key, value)
            }
        }

    /** Setter for injecting trace context into a simple Map carrier. */
    private final TextMapSetter<Map<String, String>> MAP_SETTER =
        new TextMapSetter<Map<String, String>>() {
            void set(Map<String, String> carrier, String key, String value) {
                carrier?.put(key, value)
            }
        }

    /** Getter for extracting trace context from a simple Map carrier. */
    private final TextMapGetter<Map<String, String>> MAP_GETTER =
        new TextMapGetter<Map<String, String>>() {
            Iterable<String> keys(Map<String, String> carrier) {
                carrier?.keySet() ?: Collections.emptySet() as Iterable<String>
            }
            String get(Map<String, String> carrier, String key) {
                carrier?.get(key)
            }
        }

    /** Sampler using {@link #shouldSample} — shared by the SDK pipeline and client span relay. */
    private final Sampler _sampler = new Sampler() {
        SamplingResult shouldSample(
            Context ctx,
            String traceId,
            String name,
            SpanKind kind,
            Attributes attrs,
            List parentLinks
        ) {
            TraceService.this.shouldSample(traceId) ? SamplingResult.recordAndSample() : SamplingResult.drop()
        }
        String getDescription() { "HoistTraceIdRatio(${_sampleRate})" }
    }

}
