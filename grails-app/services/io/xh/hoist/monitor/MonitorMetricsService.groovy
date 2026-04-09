/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.monitor

import groovy.transform.CompileDynamic
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.Tags
import io.xh.hoist.BaseService
import io.xh.hoist.telemetry.MetricsService

import static io.xh.hoist.monitor.MonitorStatus.UNKNOWN
import static java.lang.Double.NaN
import static java.util.concurrent.TimeUnit.MILLISECONDS

/**
 * Publishes Hoist monitor results as Micrometer metrics via {@link MetricsService},
 * enabling integration with observability platforms such as Prometheus, OTLP, and Datadog.
 *
 * Metrics are published from the primary instance only, after monitor results have been
 * aggregated by {@link io.xh.hoist.monitor.MonitorService}.
 *
 * Three metrics are published per monitor (monitor code embedded in the metric name):
 *  - `hoist.monitor.status.{code}` — Gauge of status severity (0=INACTIVE .. 4=FAIL)
 *  - `hoist.monitor.value.{code}` — Gauge of the monitor's current numeric metric
 *  - `hoist.monitor.executionTime.{code}` — Timer of the monitor's current execution time

 * In all cases, an {@code instance} tag will indicate the instance the monitor was running on,
 * with a value of 'cluster' indicating cluster-level metrics (currently status metric only).
 *
 * @internal - not intended for direct use by applications.
 */
@CompileDynamic
class MonitorMetricsService extends BaseService {

    MetricsService metricsService
    MonitorService monitorService

    private final Map<String, Meter> meters = new HashMap()

    /**
     * Ensure gauges/timers are registered for all monitors in result, and
     * record execution times.  Cull obsolete meters
     *
     * Called from MonitorService.runMonitors() on primary only.
     */
    void noteResultsUpdated(Collection<AggregateMonitorResult> results) {
        try {
            results.each { aggResult ->
                ensureAggregateMeters(aggResult)
                aggResult.results?.each {
                    ensureAndRecordInstanceMeters(it)
                }
            }
            cullMeters()
        } catch (Exception e) {
            logError('Failed to publish monitor metrics', e)
        }
    }

    //------------------------
    // Implementation
    //------------------------
    private void ensureAggregateMeters(AggregateMonitorResult aggResult) {
        def code = aggResult.monitor.code,
            name = "hoist.monitor.status.${code}"

        meters["${name}.cluster"] ?= Gauge.builder(name, this) {
            def status = monitorService.getResult(code)?.status ?: UNKNOWN
            status.severity as double
        }.tags('hoist.source', 'hoist', 'hoist.instance', 'cluster')
            .description(aggResult.monitor.name)
            .register(registry)
    }

    private void ensureAndRecordInstanceMeters(MonitorResult result) {
        //  A) Ensure all meters for this result set
        def code = result.code,
            instance = result.instance,
            tags = Tags.of('hoist.source', 'hoist', 'hoist.instance', instance),
            description = result.monitor.name

        def statusName = "hoist.monitor.status.${code}"
        meters["${statusName}.${instance}"] ?=
            Gauge.builder(statusName, this) {
                def status = getResult(code, instance)?.status ?: UNKNOWN
                status.severity as double
            }.tags(tags)
                .description(description)
                .register(registry)

        def valueName = "hoist.monitor.value.${code}"
        meters["${valueName}.${instance}"] ?=
            Gauge.builder(valueName, this) {
                def m = getResult(code, instance)?.metric
                m instanceof Number ? m.doubleValue() : NaN
            }.tags(tags)
                .description(description)
                .baseUnit(result.monitor.metricUnit ?: '')
                .register(registry)

        def execName = "hoist.monitor.executionTime.${code}"
        meters["${execName}.${instance}"] ?=
            Timer.builder(execName)
                .tags(tags)
                .description(description)
                .register(registry)

        // B) Be sure to imperatively record the timer as well
        meters["${execName}.${instance}"].record(result.elapsed, MILLISECONDS)
    }

    private void cullMeters() {
        // Remove and unregister meters for obsolete instances or monitors.
        def activeCodes = Monitor.withNewSession { Monitor.list()*.code } as Set<String>,
            activeInstances = clusterService.members.collect { it.getAttribute('instanceName') },
            staleKeys = meters.keySet().findAll { key ->
                def parts = key.split(/\./),
                    instance = parts[-1],
                    code = parts[-2]
                return !activeCodes.contains(code) ||
                    (instance != 'cluster' && !activeInstances.contains(instance))
            }

        staleKeys.each { key ->
            meters.remove(key)?.with { registry.remove(it) }
        }
    }

    private MeterRegistry getRegistry() {
        metricsService.registry
    }

    private MonitorResult getResult(String code, String instance) {
        monitorService.getResult(code)?.results?.find { it.instance == instance }
    }

    void clearCaches() {
        // Remove all meter registrations, and recreate from current monitor results.
        meters.values().each { registry.remove(it) }
        meters.clear()
        this.noteResultsUpdated(monitorService.getResults())
        super.clearCaches()
    }

    Map getAdminStats() { [
        registeredMeterCount: meters.size()
    ] }
}
