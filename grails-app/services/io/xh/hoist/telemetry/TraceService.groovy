/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.telemetry

import groovy.transform.CompileStatic
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.context.propagation.TextMapSetter
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor
import io.opentelemetry.sdk.trace.samplers.Sampler
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter
import io.opentelemetry.sdk.trace.export.SpanExporter
import io.xh.hoist.BaseService
import io.xh.hoist.config.ConfigService
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
 * Provides OpenTelemetry-based tracing with OTLP and Zipkin export, configured
 * dynamically via the {@code xhTraceConfig} soft config entry.
 *
 * Applications instrument business logic via {@link #withSpan}, available as a
 * convenience method on {@link io.xh.hoist.BaseService}. The framework auto-creates
 * request root spans and propagates context across HTTP calls and cluster execution.
 *
 * When tracing is disabled, all public methods are safe to call — they delegate to
 * no-op implementations with negligible overhead.
 *
 * This service uses the OpenTelemetry SDK directly rather than the Micrometer Tracing facade.
 * Micrometer Tracing's main value-add is unified metrics+tracing via the Observation API and
 * Spring Boot auto-instrumentation — neither of which applies here, since Hoist has its own
 * metrics system and controls its own instrumentation points (HoistFilter, JSONClient, etc.).
 * Going direct to OTel is simpler with no loss of functionality.
 */
@CompileStatic
class TraceService extends BaseService {

    static clearCachesConfigs = ['xhTraceConfig']

    ConfigService configService


    private final List<SpanExporter> _exporters = []
    private OpenTelemetrySdk _otelSdk
    private SpanExporter _otlpExporter
    private Resource _resource

    void init() {
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
     * The underlying OpenTelemetry SDK instance, or null when disabled.
     * Not typically used by applications directly. Available for advanced OTel API
     * access (e.g. custom propagation, baggage).
     */
    OpenTelemetry getOtelSdk() {
        _otelSdk
    }

    /**
     * Execute a closure within a new trace span.  Main entry point for applications.
     *
     * Creates a child span if a parent context exists, or a root span otherwise.
     * Attributes from {@code tags} are set on the span. Exceptions are recorded
     * on the span and re-thrown.
     *
     * @param name - span name (e.g. 'processOrder', 'loadPortfolio')
     * @param tags - optional key-value attributes to set on the span
     * @param c - closure to execute within the span
     * @return result of the closure
     */
    <T> T withSpan(String name, Map<String, String> tags = [:], Closure<T> c) {
        doWithSpan(name, SpanKind.INTERNAL, null, tags, c)
    }

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

    /**
     * Capture the current trace context and return a wrapper closure that restores it.
     * Useful for propagating context across async boundaries (thread pools, callbacks).
     */
    Closure wrapContext(Closure c) {
        enabled ? { Context.current().makeCurrent().withCloseable { c.call() } } : c
    }


    //--------------------------------------------------
    // Package-private: for Hoist internal instrumentation
    //--------------------------------------------------
    /**
     * Configured span exporters for direct export of client-relayed spans.
     * @internal
     */
    List<SpanExporter> getExporters() { _exporters.asImmutable() }

    /**
     * The OTel Resource describing this application instance.
     * @internal
     */
    Resource getTracingResource() { _resource }

    /**
     * Create a SERVER span with an explicit parent context.
     *
     * @internal -- Used by HoistFilter for request root spans with extracted W3C trace context.
     */
    <T> T withServerSpan(String name, Context parentContext, Map<String, String> tags, Closure<T> c) {
        doWithSpan(name, SpanKind.SERVER, parentContext, tags, c)
    }

    /**
     * Create a CLIENT span.
     *
     * @internal -- Used by JSONClient and BaseProxyService for outbound HTTP calls.
     */
    <T> T withClientSpan(String name, Map<String, String> tags, Closure<T> c) {
        doWithSpan(name, SpanKind.CLIENT, null, tags, c)
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

    private <T> T doWithSpan(
        String name,
        SpanKind kind,
        Context parentContext,
        Map<String, String> tags,
        Closure<T> c
    ) {
        if (!enabled) return c.call()

        def spanBuilder = _otelSdk.getTracer('io.xh.hoist').spanBuilder(name)
            .setSpanKind(kind)
        if (parentContext) spanBuilder.setParent(parentContext)

        tags.each { k, v -> spanBuilder.setAttribute(AttributeKey.stringKey(k), v) }
        if (!tags.source) spanBuilder.setAttribute(AttributeKey.stringKey('source'), 'app')

        def span = spanBuilder.startSpan(),
            scope = span.makeCurrent()
        try {
            return c.call()
        } catch (Throwable t) {
            span.setStatus(StatusCode.ERROR, t.message ?: t.class.name)
            span.recordException(t)
            throw t
        } finally {
            span.end()
            scope.close()
        }
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
                    .put(AttributeKey.stringKey('service.name'), appCode)
                    .put(AttributeKey.stringKey('service.instance.id'), instanceName)
                    .put(AttributeKey.stringKey('deployment.environment'), appEnvironment.toString())
                    .put(AttributeKey.stringKey('service.version'), appVersion)
                    .build()
                )
            )

            def sampler = Sampler.traceIdRatioBased(config.sampleRate)

            def providerBuilder = SdkTracerProvider.builder()
                .setResource(_resource)
                .setSampler(sampler)

            // Built-in OTLP exporter
            if (_otlpExporter) {
                exporters.remove(_otlpExporter)
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
                _otlpExporter = otlpBuilder.build()
                exporters.add(_otlpExporter)
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
        config: configForAdminStats('xhTraceConfig')
    ]}

    /** Shared setter for injecting trace context onto outbound Apache HTTP requests. */
    static final TextMapSetter<HttpUriRequestBase> HTTP_SETTER =
        new TextMapSetter<HttpUriRequestBase>() {
            void set(HttpUriRequestBase carrier, String key, String value) {
                carrier?.setHeader(key, value)
            }
        }
}
