/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.telemetry.trace.impl

import grails.util.Holders
import groovy.transform.CompileDynamic
import groovy.transform.CompileStatic
import io.opentelemetry.instrumentation.jdbc.datasource.JdbcTelemetry
import io.opentelemetry.instrumentation.jdbc.datasource.OpenTelemetryDataSource
import io.xh.hoist.BaseService
import io.xh.hoist.config.ConfigService
import io.xh.hoist.telemetry.trace.DelegatingOpenTelemetry
import io.xh.hoist.telemetry.trace.TraceConfig
import io.xh.hoist.telemetry.trace.TraceService

import javax.sql.DataSource
import org.hibernate.SessionFactory
import org.hibernate.engine.jdbc.connections.spi.ConnectionProvider

/**
 * Installs OpenTelemetry JDBC instrumentation across all pools,
 * syncing install/uninstall with the current trace config.
 *
 * @internal - not intended for direct use by applications.
 */
@CompileStatic
class JdbcTraceService extends BaseService {

    static clearCachesConfigs = ['xhTraceConfig']

    ConfigService configService
    TraceService traceService

    // parent proxy -> original raw pool (to restore on uninstall)
    private final Map<DataSource, DataSource> _jdbcWrapSites = [:]

    void init() {
        syncJdbcConfig()
    }

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
