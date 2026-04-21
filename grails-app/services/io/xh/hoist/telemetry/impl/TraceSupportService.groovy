/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.telemetry.impl

import grails.async.Promises
import grails.util.Holders
import groovy.transform.CompileDynamic
import groovy.transform.CompileStatic
import io.opentelemetry.api.trace.Span
import io.opentelemetry.context.Context
import io.opentelemetry.context.Scope
import io.opentelemetry.context.propagation.TextMapGetter
import io.opentelemetry.context.propagation.TextMapSetter
import io.opentelemetry.instrumentation.jdbc.datasource.JdbcTelemetry
import io.opentelemetry.instrumentation.jdbc.datasource.OpenTelemetryDataSource
import io.xh.hoist.BaseService
import io.xh.hoist.config.ConfigService
import io.xh.hoist.telemetry.ContextPropagatingPromiseFactory
import io.xh.hoist.telemetry.TraceConfig
import io.xh.hoist.telemetry.TraceService
import io.xh.hoist.telemetry.DelegatingOpenTelemetry
import jakarta.servlet.http.HttpServletRequest
import javax.sql.DataSource
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase
import org.hibernate.SessionFactory
import org.hibernate.engine.jdbc.connections.spi.ConnectionProvider

import static java.util.Collections.emptySet

/**
 * Internal support service for {@link TraceService}. Hosts W3C trace-context propagation
 * plumbing used by framework-level instrumentation (inbound request filter, outbound
 * HTTP/proxy clients, cluster task hand-off). Installs OpenTelemetry JDBC instrumentation
 * across all pools.  Provides support for tracing via Hoist PromiseFactory.
 *
 * @internal - not intended for direct use by applications.
 */
@CompileStatic
class TraceSupportService extends BaseService {

    static clearCachesConfigs = ['xhTraceConfig']

    ConfigService configService
    TraceService traceService

    // parent proxy -> original raw pool (to restore on uninstall)
    private final Map<DataSource, DataSource> _jdbcWrapSites = [:]

    void init() {
        installPromiseContextPropagation()
        syncJdbcConfig()
    }

    //--------------------------------
    // HTTP
    //---------------------------------
    /**
     * Inject W3C trace context onto an outbound HTTP request.
     * No-op if tracing is disabled or no active span exists.
     */
    void injectContext(HttpUriRequestBase request) {
        def sdk = traceService.otelSdk
        if (!sdk || !Span.current().spanContext.valid) return
        sdk.propagators.textMapPropagator.inject(Context.current(), request, HTTP_SETTER)
    }

    /**
     * Capture the current trace context as a W3C traceparent string.
     * Returns null if tracing is disabled or no active span exists.
     */
    String captureTraceparent() {
        def sdk = traceService.otelSdk
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
        def sdk = traceService.otelSdk
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
        def sdk = traceService.otelSdk
        if (!sdk) return null
        def context = sdk.propagators.textMapPropagator.extract(Context.current(), request, HTTP_GETTER)
        context.makeCurrent()
    }

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
    private static final TextMapSetter<HttpUriRequestBase> HTTP_SETTER =
        new TextMapSetter<HttpUriRequestBase>() {
            void set(HttpUriRequestBase carrier, String key, String value) {
                carrier?.setHeader(key, value)
            }
        }

    /** Setter for injecting trace context into a simple Map carrier. */
    private static final TextMapSetter<Map<String, String>> MAP_SETTER =
        new TextMapSetter<Map<String, String>>() {
            void set(Map<String, String> carrier, String key, String value) {
                carrier?.put(key, value)
            }
        }

    /** Getter for extracting trace context from a simple Map carrier. */
    private static final TextMapGetter<Map<String, String>> MAP_GETTER =
        new TextMapGetter<Map<String, String>>() {
            Iterable<String> keys(Map<String, String> carrier) {
                carrier?.keySet() ?: emptySet() as Iterable<String>
            }

            String get(Map<String, String> carrier, String key) {
                carrier?.get(key)
            }
        }


    //------------
    // Promises
    //------------
    /**
     * Install a delegating PromiseFactory that propagates OTel trace context to worker
     * threads spawned by Grails {@code task {}} calls. Installed once at startup.
     */
    private void installPromiseContextPropagation() {
        if (!(Promises.promiseFactory instanceof ContextPropagatingPromiseFactory)) {
            Promises.promiseFactory = new ContextPropagatingPromiseFactory(Promises.promiseFactory)
        }
    }

    //---------------
    // JDBC
    //---------------
    /**
     * Install or uninstall JDBC instrumentation based on the current effective-enabled state
     * ({@code enabled && jdbcTracingEnabled}). Installing is a no-op when wraps are already
     * in place; uninstalling restores each wrap site to the original raw pool. Keeping the
     * wrap out of the chain when disabled avoids per-statement OTel overhead (SQL sanitization,
     * attribute extractors) entirely.
     */
    private synchronized void syncJdbcConfig() {
        def config = new TraceConfig(configService.getMap('xhTraceConfig')),
            shouldBeInstalled = config.enabled && config.jdbcTracingEnabled,
            isInstalled = !_jdbcWrapSites.isEmpty()

        if (shouldBeInstalled != isInstalled) {
            shouldBeInstalled ? installWraps() : uninstallWraps()
        }
    }

    /** Reusable wrapper used while JDBC instrumentation is active; resolves SDK per span. */
    private final JdbcTelemetry jdbcTelemetry = JdbcTelemetry.create(
        new DelegatingOpenTelemetry({traceService.otelSdk})
    )

    /**
     * Install OpenTelemetry JDBC instrumentation into every {@code DataSource} reachable from
     * the Spring context — including both Spring-managed DataSource beans (for direct JDBC
     * consumers) and the DataSources held internally by each Hibernate {@code SessionFactory}
     * (which Grails' Hibernate plugin captures early, bypassing any post-processing of the
     * Spring bean).
     *
     * For each target DataSource we walk down its proxy chain to the underlying raw pool and
     * swap it for an {@link OpenTelemetryDataSource} wrap. Wrapping at the bottom of the chain
     * means every consumer that shares the chain (Spring DI, direct JDBC, Hibernate) is
     * covered by a single wrap. Each install is recorded in {@code _jdbcWrapSites} so we can
     * restore the original chain in {@link #uninstallWraps}.
     */
    @CompileDynamic
    private void installWraps() {
        def ctx = Holders.applicationContext

        // Direct DataSource beans (covers apps doing raw JDBC via Utils.dataSource, JdbcTemplate, etc.)
        ctx.getBeanNamesForType(DataSource).each { dsName ->
            wrapAtChainBottom(dsName) { ctx.getBean(dsName) as DataSource }
        }

        // Hibernate SessionFactory internals (its ConnectionProvider holds its own chain instance)
        ctx.getBeanNamesForType(SessionFactory).each { sfName ->
            def dsName = sfName.replaceFirst(/^sessionFactory/, 'dataSource')
            wrapAtChainBottom(dsName) {
                def cp = ctx.getBean(sfName).sessionFactoryOptions.serviceRegistry.getService(ConnectionProvider)
                cp.dataSource as DataSource
            }
        }
    }

    /**
     * Restore every previously wrapped chain to its original raw pool. Safe to call even if
     * the chain has been mutated since install — we only unwrap sites that still contain an
     * {@link OpenTelemetryDataSource} where we left one.
     */
    @CompileDynamic
    private void uninstallWraps() {
        _jdbcWrapSites.each { parent, rawPool ->
            try {
                if (parent.targetDataSource instanceof OpenTelemetryDataSource) {
                    parent.targetDataSource = rawPool
                }
            } catch (Throwable t) {
                logWarn("Failed to unwrap DataSource", t)
            }
        }
        _jdbcWrapSites.clear()
        logInfo("Uninstalled all JDBC DataSource instrumentation")
    }

    /**
     * Walk the DataSource delegation chain from {@code chainRoot()} down to the raw pool and
     * replace that pool with an {@link OpenTelemetryDataSource} wrap. Idempotent — skips if the
     * chain already contains an OpenTelemetryDataSource.
     *
     * Uses Groovy dynamic dispatch on {@code targetDataSource} so we don't have to bind to any
     * specific proxy type (TransactionAwareDataSourceProxy / LazyConnectionDataSourceProxy /
     * DelegatingDataSource all expose it).
     */
    @CompileDynamic
    private void wrapAtChainBottom(String beanName, Closure<DataSource> chainRoot) {
        try {
            def parent = null
            def current = chainRoot.call()
            while (current?.hasProperty('targetDataSource') && current.targetDataSource != null) {
                if (current.targetDataSource instanceof OpenTelemetryDataSource) return
                parent = current
                current = current.targetDataSource
            }
            if (parent == null) {
                throw new IllegalStateException("No proxy chain to insert instrumentation into")
            }
            parent.targetDataSource = jdbcTelemetry.wrap(current as DataSource)
            _jdbcWrapSites[parent as DataSource] = current as DataSource
            logInfo("Wrapped DataSource '$beanName' with OpenTelemetryDataSource")
        } catch (Throwable t) {
            logWarn("Failed to wrap DataSource '$beanName' - JDBC tracing may be unavailable", t)
        }
    }

    Map getAdminStats() {[
        config: configForAdminStats('xhTraceConfig')
    ]}

    void clearCaches() {
        super.clearCaches()
        syncJdbcConfig()
    }
}
