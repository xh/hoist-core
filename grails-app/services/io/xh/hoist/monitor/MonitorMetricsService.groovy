/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.monitor

import groovy.transform.CompileDynamic
import io.xh.hoist.BaseService
import io.xh.hoist.config.ConfigService
import io.xh.hoist.util.Utils
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.Timer

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Publishes Micrometer metrics for Hoist monitor results, enabling integration with observability
 * platforms such as Prometheus, Grafana, and Datadog.
 *
 * Metrics are published from the primary instance only, after monitor results have been aggregated
 * by {@link MonitorService}.
 *
 * Two named metrics are published per monitor (monitor code embedded in the metric name):
 *  - `{ns}.{code}.status`    — Gauge of status severity (0=INACTIVE .. 4=FAIL)
 *  - `{ns}.{code}.value`     — Gauge of the monitor's current numeric metric
 *
 * Two additional metrics provide support for global queries (monitor code is a tag):
 *   - `{ns}.status`
 *   - `{ns}.executionTime`
 *
 * In all cases, an instance tag will indicate the instance the monitor was running on, with a
 * value of 'aggregate' indicating cluster-level metrics (currently status only).
 *
 * The namespace prefix defaults to the application code (e.g., `myapp.monitor`)
 * and can be overridden via the `metricsNamespace` key in `xhMonitorConfig`.
 *
 * @internal - not intended for direct use by applications.
 */
@CompileDynamic
class MonitorMetricsService extends BaseService {

    ConfigService configService
    MeterRegistry meterRegistry

    private final ConcurrentHashMap<String, Number> lastVals = new ConcurrentHashMap()

    void init() {
        if (!meterRegistry) {
            logWarn('MeterRegistry not available — monitor metrics will not be published')
        }
    }

    /**
     * Publish per-instance metrics for all monitors after aggregation on the primary.
     * Called from MonitorService.runMonitors() — failures here must not break monitoring.
     */
    void publishResults(Collection<AggregateMonitorResult> results) {
        if (!enabled) return
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
    private void publishAggregateStatus(AggregateMonitorResult aggResult) {
        def code = aggResult.monitor.code,
            key = "$code|status",
            description = 'Aggregate monitor status severity (0=INACTIVE .. 4=FAIL)'
        if (!lastVals.containsKey(key)) {
            // a) publish code *namespaced* status
            Gauge.builder(metricName("{$code}.status"), lastVals, readDouble(key))
                .tags(baseTags(aggResult.monitor, 'aggregate'))
                .description(description)
                .register(meterRegistry)

            // b) publish code *tagged* status
            Gauge.builder(metricName('status'), lastVals, readDouble(key))
                .tags(baseTags(aggResult.monitor, 'aggregate'))
                .tag('monitorCode', code)
                .description(description)
                .register(meterRegistry)
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
            key = "$code|$instance|status",
            description = 'Monitor status severity (0=INACTIVE .. 4=FAIL)'

        if (!lastVals.containsKey(key)) {
            // a) publish code *namespaced* status
            Gauge.builder(metricName("${code}.status"), lastVals, readDouble(key))
                .tags(baseTags(result.monitor, instance))
                .description(description)
                .register(meterRegistry)

            // b) publish code *tagged* status
            Gauge.builder(metricName('status'), lastVals, readDouble(key))
                .tags(baseTags(result.monitor, instance))
                .tag('monitorCode', code)
                .description(description)
                .register(meterRegistry)
        }

        lastVals.put(key, result.status.severity)
    }

    private void publishValue(MonitorResult result) {
        if (!(result.metric instanceof Number)) return

        def code = result.code,
            instance = result.instance,
            key = "$code|$instance|value"

        if (!lastVals.containsKey(key)) {
            Gauge.builder(metricName("${code}.value"), lastVals, readDouble(key))
                .tags(baseTags(result.monitor, instance))
                .baseUnit(result.monitor.metricUnit ?: '')
                .description(result.monitor.name)
                .register(meterRegistry)
        }
        lastVals.put(key, result.metric as Number)
    }

    private void publishExecution(MonitorResult result) {
        if (!result.elapsed) return
        Timer.builder(metricName('executionTime'))
            .tags(baseTags(result.monitor, result.instance))
            .description('Monitor execution time')
            .register(meterRegistry)
            .record(result.elapsed, TimeUnit.MILLISECONDS)
    }

    private Tags baseTags(Monitor monitor, String instance) {
        Tags.of(
            'application', Utils.appCode,
            'instance', instance,
            'primaryOnly', monitor.primaryOnly.toString(),
        )
    }

    private Closure readDouble(String key) {
        return { it.get(key)?.doubleValue() ?: 0.0 }
    }

    private String metricName(String suffix) {
        "${namespace}.${suffix}"
    }

    private String getNamespace() {
        "${config.metricsNamespace ?: Utils.appCode}.monitor"
    }

    private boolean getEnabled() {
        meterRegistry && config.metricsEnabled != false
    }

    private MonitorConfig getConfig() {
        new MonitorConfig(configService.getMap('xhMonitorConfig'))
    }

    void clearCaches() {
        super.clearCaches()
        lastVals.clear()
    }

    Map getAdminStats() {
        [
            config: configForAdminStats('xhMonitorConfig'),
            enabled: enabled,
            gaugeCount: lastVals.size()
        ]
    }
}