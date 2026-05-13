/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.telemetry.metric.impl

import groovy.transform.CompileDynamic
import groovy.transform.CompileStatic
import io.micrometer.core.instrument.binder.MeterBinder
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics
import io.micrometer.core.instrument.binder.jvm.JvmCompilationMetrics
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics
import io.micrometer.core.instrument.binder.jvm.JvmHeapPressureMetrics
import io.micrometer.core.instrument.binder.jvm.JvmInfoMetrics
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics
import io.micrometer.core.instrument.binder.logging.LogbackMetrics
import io.micrometer.core.instrument.binder.system.FileDescriptorMetrics
import io.micrometer.core.instrument.binder.system.ProcessorMetrics
import io.micrometer.core.instrument.binder.system.UptimeMetrics
import io.micrometer.core.instrument.binder.tomcat.TomcatMetrics
import io.xh.hoist.BaseService
import io.xh.hoist.telemetry.metric.MetricsService
import org.apache.tomcat.jdbc.pool.DataSource as PooledDataSource

import javax.sql.DataSource

/**
 * Registers standard infrastructure metrics with the application's
 * {@link MetricsService#registry}. Covers JVM internals, system/process
 * stats, Tomcat, logging, and JDBC connection pool usage.
 *
 * All metrics registered here receive {@code source: hoist} via the
 * default tag filter in {@link MetricsService}.
 *
 * @internal - not intended for direct use by applications.
 */
@CompileStatic
class BuiltInMetricsService extends BaseService {

    def dataSource

    MetricsService metricsService

    void init() {
        registerProvidedMeters()
        registerConnectionPoolMeters()
    }

    //------------------------
    // Implementation
    //------------------------
    /**
     * Standard Micrometer provided meters -- included in Spring Boot autoconfigure
     */
    private void registerProvidedMeters() {
        List<MeterBinder> binders = [
            // JVM
            new ClassLoaderMetrics(),
            new JvmCompilationMetrics(),
            new JvmGcMetrics(),
            new JvmHeapPressureMetrics(),
            new JvmInfoMetrics(),
            new JvmMemoryMetrics(),
            new JvmThreadMetrics(),

            // System
            new FileDescriptorMetrics(),
            new ProcessorMetrics(),
            new UptimeMetrics(),

            // Logging
            new LogbackMetrics(),

            // Tomcat
            new TomcatMetrics(null, [])
        ]
        binders.each { it.bindTo(metricsService.registry) }
    }

    @CompileDynamic
    /**
     * Includes standard + tomcat pool specific
     */
    private void registerConnectionPoolMeters() {
        def ds = dataSource as DataSource

        ds = ds.isWrapperFor(PooledDataSource) ? ds.unwrap(PooledDataSource) : null
        if (!ds) {
            logWarn("Primary DataSource not org.apache.tomcat.jdbc.pool.DataSource - JDBC metrics unavailable.")
            return
        }
        def readDsProp = {String prop -> { ds[prop] ?: 0d } as Closure}

        def gauge = { String suffix, String prop, String desc ->
            metricsService.registerGauge(
                name: "jdbc.connections.${suffix}",
                valueFn: readDsProp(prop),
                description: desc,
                owner: this,
                useNamePrefix: false
            )
        }
        def fnCounter = { String suffix, String prop, String desc ->
            metricsService.registerFunctionCounter(
                name: "jdbc.connections.${suffix}",
                countFn: readDsProp(prop),
                description: desc,
                owner: this,
                useNamePrefix: false
            )
        }

        // Spring Boot standard metrics -- generic for all pools.
        gauge('active', 'active', 'Active/in-use connections')
        gauge('idle', 'idle', 'Idle connections')
        gauge('max', 'maxActive', 'Maximum pool size')
        gauge('min', 'minIdle', 'Minimum idle connections')

        // Tomcat JDBC pool specifics
        gauge('pending', 'waitCount', 'Threads waiting for a connection')
        fnCounter('borrowed', 'borrowedCount', 'Connections borrowed from pool')
        fnCounter('returned', 'returnedCount', 'Connections returned to pool')
        fnCounter('created', 'createdCount', 'Connections created')
        fnCounter('released', 'releasedCount', 'Connections released/destroyed')
        fnCounter('reconnected', 'reconnectedCount', 'Connections re-established after failure')
        fnCounter('abandoned', 'removeAbandonedCount', 'Connections removed due to abandonment')
        fnCounter('evicted', 'releasedIdleCount', 'Idle connections released by evictor')
    }
}
