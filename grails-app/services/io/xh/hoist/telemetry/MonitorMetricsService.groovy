/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.telemetry

import groovy.transform.CompileDynamic
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.xh.hoist.BaseService
import io.xh.hoist.monitor.AggregateMonitorResult
import io.xh.hoist.monitor.MonitorResult

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

import static io.xh.hoist.telemetry.MetricsService.CLUSTER_TAG

/**
 * Publishes Hoist monitor results as Micrometer metrics via {@link MetricsService},
 * enabling integration with observability platforms such as Prometheus, OTLP, and Datadog.
 *
 * Metrics are published from the primary instance only, after monitor results have been
 * aggregated by {@link io.xh.hoist.monitor.MonitorService}.
 *
 * Three metrics are published per monitor (monitor code embedded in the metric name):
 *  - `hoist.monitor.{code}.status` — Gauge of status severity (0=INACTIVE .. 4=FAIL)
 *  - `hoist.monitor.{code}.value` — Gauge of the monitor's current numeric metric
 *  - `hoist.monitor.{code}.executionTime` — Timer of the monitor's current execution time
 *
 * An additional generic status metric provides support for global status queries (monitor code is a tag):
 *  - `hoist.monitor.status`
 *
 * In all cases, an {@code instance} tag will indicate the instance the monitor was running on,
 * with a value of 'cluster' indicating cluster-level metrics (currently status only).
 *
 * @internal - not intended for direct use by applications.
 */
@CompileDynamic
class MonitorMetricsService extends BaseService {

    MetricsService metricsService

    private final ConcurrentHashMap<String, Number> lastVals = new ConcurrentHashMap()

    /**
     * Publish per-instance metrics for all monitors after aggregation on the primary.
     * Called from MonitorService.runMonitors() — failures here must not break monitoring.
     */
    void publishResults(Collection<AggregateMonitorResult> results) {
        try {
            results.each { aggResult ->
                publishAggregateStatus(aggResult)
                aggResult.results?.each { publishInstanceResult(it) }
            }
        } catch (Exception e) {
            logError('Failed to publish monitor metrics', e)
        }
    }

    //------------------------
    // Implementation
    //------------------------
    private MeterRegistry getRegistry() {
        metricsService.registry
    }

    private void publishAggregateStatus(AggregateMonitorResult aggResult) {
        def monitor = aggResult.monitor,
            code = monitor.code,
            key = "$code|status"

         if (!lastVals.containsKey(key)) {
            def description = monitor.name

            Gauge.builder("monitor.${code}.status", lastVals, readDouble(key))
                .tag('source', 'hoist')
                .tags('instance', CLUSTER_TAG)
                .description(description)
                .register(registry)

            Gauge.builder('monitor.status', lastVals, readDouble(key))
                .tag('source', 'hoist')
                .tags('instance', CLUSTER_TAG)
                .tag('monitorCode', code)
                .description(description)
                .register(registry)
        }
        lastVals.put(key, aggResult.status.severity)
    }

    private void publishInstanceResult(MonitorResult result) {
        publishStatus(result)
        publishValue(result)
        publishExecution(result)
    }

    private void publishStatus(MonitorResult result) {
        def code = result.code,
            instance = result.instance,
            key = "$code|$instance|status"

        if (!lastVals.containsKey(key)) {

            def description = result.monitor.name

            Gauge.builder("monitor.${code}.status", lastVals, readDouble(key))
                .tag('source', 'hoist')
                .description(description)
                .register(registry)

            Gauge.builder('monitor.status', lastVals, readDouble(key))
                .tag('source', 'hoist')
                .tag('monitorCode', code)
                .description(description)
                .register(registry)
        }

        lastVals.put(key, result.status.severity)
    }

    private void publishValue(MonitorResult result) {
        if (!(result.metric instanceof Number)) return

        def code = result.code,
            instance = result.instance,
            key = "$code|$instance|value",
            monitor = result.monitor

        if (!lastVals.containsKey(key)) {
            Gauge.builder("monitor.${code}.value", lastVals, readDouble(key))
                .tag('source', 'hoist')
                .description(monitor.name)
                .baseUnit(monitor.metricUnit ?: '')
                .register(registry)
        }
        lastVals.put(key, result.metric as Number)
    }

    private void publishExecution(MonitorResult result) {
        if (!result.elapsed) return
        Timer.builder("monitor.${result.code}.executionTime")
            .tag('source', 'hoist')
            .description(result.monitor.name)
            .register(registry)
            .record(result.elapsed, TimeUnit.MILLISECONDS)
    }

    private Closure readDouble(String key) {
        return { it.get(key)?.doubleValue() ?: 0.0d }
    }

    void clearCaches() {
        super.clearCaches()
        lastVals.clear()
    }

    Map getAdminStats() { [
        gaugeCount: lastVals.size()
    ] }
}