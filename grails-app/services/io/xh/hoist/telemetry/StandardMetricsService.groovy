/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.telemetry

import groovy.transform.CompileDynamic
import groovy.transform.CompileStatic
import io.micrometer.core.instrument.FunctionCounter
import io.micrometer.core.instrument.Gauge
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
import org.apache.tomcat.jdbc.pool.DataSource as PooledDataSource
import org.springframework.boot.jdbc.DataSourceUnwrapper

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
class StandardMetricsService extends BaseService {

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
        def readDsProp
        try {
            def _pooledDataSource = DataSourceUnwrapper.unwrap(dataSource as DataSource, PooledDataSource.class)
            readDsProp = {String prop -> {_pooledDataSource[prop] as double} as Closure}
        } catch (e) {
            logError("Primary DataSource not org.apache.tomcat.jdbc.pool.DataSource - JDBC metrics unavailable.", e)
            return
        }

        def prefix = 'jdbc.connections'
        List builders = [

            // Spring Boot standard metrics -- generic for all pools.
            Gauge.builder("${prefix}.active", this, readDsProp('active'))
                .description('Active/in-use connections'),

            Gauge.builder("${prefix}.idle", this, readDsProp('idle'))
                .description('Idle connections'),

            Gauge.builder("${prefix}.max", this, readDsProp('maxActive'))
                .description('Maximum pool size'),

            Gauge.builder("${prefix}.min", this, readDsProp('minIdle'))
                .description('Minimum idle connections'),

            // Tomcat JDBC pool specifics
            Gauge.builder("${prefix}.pending", this, readDsProp('waitCount'))
                .description('Threads waiting for a connection'),

            FunctionCounter.builder("${prefix}.borrowed", this, readDsProp('borrowedCount'))
                .description('Connections borrowed from pool'),

            FunctionCounter.builder("${prefix}.returned", this, readDsProp('returnedCount'))
                .description('Connections returned to pool'),

            FunctionCounter.builder("${prefix}.created", this, readDsProp('createdCount'))
                .description('Connections created'),

            FunctionCounter.builder("${prefix}.released", this, readDsProp('releasedCount'))
                .description('Connections released/destroyed'),

            FunctionCounter.builder("${prefix}.reconnected", this, readDsProp('reconnectedCount'))
                .description('Connections re-established after failure'),

            FunctionCounter.builder("${prefix}.abandoned", this, readDsProp('removeAbandonedCount'))
                .description('Connections removed due to abandonment'),

            FunctionCounter.builder("${prefix}.evicted", this, readDsProp('releasedIdleCount'))
                .description('Idle connections released by evictor')
        ]

        builders.each {
            it.register(metricsService.registry)
        }
    }
}
