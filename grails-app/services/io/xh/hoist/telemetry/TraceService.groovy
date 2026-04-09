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
import io.opentelemetry.sdk.trace.data.SpanData
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

import static io.xh.hoist.cluster.ClusterService.otelResourceAttributes
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

    private List<SpanExporter> _customExporters = []
    private OpenTelemetrySdk _otelSdk
    private SpanExporter _otlpExporter
    private Resource _resource
    private double _sampleRate = 1.0
    private Counter _spansRequested
    private Counter _spansCreated


    void init() {
        def registry = metricsService.registry
        _spansRequested = Counter.builder('hoist.trace.spans.requested')
            .description('Total spans evaluated for sampling — includes server and client-relayed spans')
            .register(registry)
        _spansCreated = Counter.builder('hoist.trace.spans.created')
            .description('Total spans that passed sampling — includes server and client-relayed spans')
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
        def sdk = _otelSdk
        if (!sdk) return null

        def spanBuilder = sdk.getTracer('io.xh.hoist').spanBuilder(name).setSpanKind(kind),
            span = spanBuilder.startSpan(),
            pending = new SpanRef(span, span.makeCurrent(), kind)

        pending.setTags(tags)
        if (!tags['xh.source']) pending.setTag('xh.source', 'app')
        if (caller) pending.setTag('code.namespace', caller.class.name)
        if (username) pending.setTag('user.name', username)

        return pending
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


    //---------------------------
    // Context Propagation
    //---------------------------
    /**
     * Inject W3C trace context onto an outbound HTTP request.
     * No-op if tracing is disabled or no active span exists.
     */
    void injectContext(HttpUriRequestBase request) {
        def sdk = _otelSdk
        if (!sdk || !Span.current().spanContext.valid) return
        sdk.propagators.textMapPropagator.inject(Context.current(), request, HTTP_SETTER)
    }

    /**
     * Capture the current trace context as a W3C traceparent string.
     * Returns null if tracing is disabled or no active span exists.
     */
    String captureTraceparent() {
        def sdk = _otelSdk
        if (!sdk || !Span.current().spanContext.valid) return null
        Map<String, String> carrier = [:]
        sdk.propagators.textMapPropagator.inject(Context.current(), carrier, MAP_SETTER)
        carrier.traceparent
    }

    /**
     * Restore a previously captured traceparent string as the current context.
     *
     * Returns a {@link Scope} that must be closed when done (typically in a finally block),
     * or null if the traceparent is null/empty or tracing is disabled.
     */
    Scope restoreContextFromTraceparent(String traceparent) {
        def sdk = _otelSdk
        if (!traceparent || !sdk) return null
        def context = sdk.propagators.textMapPropagator.extract(Context.current(), [traceparent: traceparent], MAP_GETTER)
        context.makeCurrent()
    }

    /**
     * Restore a W3C trace parent context from incoming HTTP request headers.
     *
     * Returns a {@link Scope} that must be closed when done (typically in a finally block),
     * or null if the traceparent is null/empty or tracing is disabled.
     */
    Scope restoreContextFromRequest(HttpServletRequest request) {
        def sdk = _otelSdk
        if (!sdk) return null
        def context = sdk.propagators.textMapPropagator.extract(Context.current(), request, HTTP_GETTER)
        context.makeCurrent()
    }


    //--------------------------------------------------
    // Framework-internal
    //--------------------------------------------------
    /**
     * Submit client-side spans received from the browse.
     *
     * Converts the client span JSON into OTel {@link io.opentelemetry.sdk.trace.data.SpanData} objects and exports them
     * through the configured export pipeline, preserving the original trace/span IDs so
     * that client and server spans form a coherent distributed trace.
     *
     * Spans are filtered using the same deterministic trace-ID-based sampling as
     * server-originated spans, ensuring consistent sampling across both paths.
     *
     * @param spans - list of span maps as serialized by the client {@code Span.toJSON()}
     */
    void submitClientSpans(List<Map> spans) {
        def resource = _resource
        if (!resource) return

        def data = spans
            .findAll { shouldSample(it.traceId as String) }
            .collect { new ClientSpanData(it, resource) } as List<SpanData>

        eachExporter { SpanExporter e ->
            e.export(data)
        }
    }


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
        if (_sampleRate >= 1.0) {
            ret = true
        } else if (_sampleRate <= 0.0) {
            ret = false
        } else {
            try {
                long lowerLong = Long.parseUnsignedLong(traceId.substring(16), 16)
                ret = Long.compareUnsigned(lowerLong, (long) (Long.MAX_VALUE * _sampleRate)) < 0
            } catch (Exception ignored) {
                ret = false
            }
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
        if (!(Promises.promiseFactory instanceof ContextPropagatingPromiseFactory)) {
            Promises.promiseFactory = new ContextPropagatingPromiseFactory(Promises.promiseFactory)
        }
    }

    private TraceConfig getConfig() {
        new TraceConfig(configService.getMap('xhTraceConfig'))
    }

    private synchronized void syncConfig() {
        def config = getConfig()

        withDebug(['Syncing tracing pipeline', [enabled: config.enabled, otlp: config.otlpEnabled]]) {
            shutdownProvider()

            if (!config.enabled) return

            def attrsBuilder = Attributes.builder()
            otelResourceAttributes.each { k, v -> attrsBuilder.put(stringKey(k), v) }
            _resource = Resource.default.merge(Resource.create(attrsBuilder.build()))

            _sampleRate = config.sampleRate

            def providerBuilder = SdkTracerProvider.builder()
                .setResource(_resource)
                .setSampler(_sampler)

            // Built-in OTLP exporter
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
            } else {
                _otlpExporter = null
            }

            // Add all exporters
            eachExporter {SpanExporter e ->
                providerBuilder.addSpanProcessor(BatchSpanProcessor.builder(e).build())
            }

            _otelSdk = OpenTelemetrySdk.builder()
                .setTracerProvider(providerBuilder.build())
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.instance))
                .build()
        }
    }

    private void eachExporter(Closure c) {
        _customExporters.each(c)
        def _otlp = _otlpExporter
        if (_otlp) c(_otlp)
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
    private final TextMapGetter<HttpServletRequest> HTTP_GETTER =
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
