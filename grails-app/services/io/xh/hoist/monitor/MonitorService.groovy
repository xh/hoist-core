/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.monitor

import grails.compiler.GrailsCompileStatic
import grails.gorm.transactions.ReadOnly
import groovy.transform.CompileDynamic
import io.xh.hoist.BaseService
import io.xh.hoist.cachedvalue.CachedValue
import io.xh.hoist.cluster.ClusterRequest
import io.xh.hoist.config.ConfigService
import io.xh.hoist.util.Timer

import static AggregateMonitorResult.emptyResults
import static AggregateMonitorResult.newResults
import static grails.util.Environment.isDevelopmentMode
import static io.xh.hoist.util.DateTimeUtils.MINUTES
import static io.xh.hoist.util.Utils.getAppContext

/**
 * Coordinates application status monitoring across all cluster instances. The primary instance
 * will request and correlate status monitor runs on each cluster member and make them globally
 * available as a consolidated report.
 *
 * Frequency of monitor runs can be adjusted via the `xhMonitorConfig` config, with an additional
 * `xhEnableMonitoring` config available to completely disable this feature.
 *
 * In local development mode, auto-run/refresh of Monitors is disabled, but monitors can still be
 * run on demand via `forceRun()`.
 */
@GrailsCompileStatic
class MonitorService extends BaseService {

    ConfigService configService
    MonitorReportService monitorReportService

    // Shared state for all servers to read - gathered by primary from all instances.
    // Map of monitor code to aggregated (cross-instance) results.
    private CachedValue<Map<String, AggregateMonitorResult>> _results = createCachedValue(
        name: 'results',
        replicate: true
    )

    private Timer timer

    void init() {
        timer = createTimer(
            name: 'runMonitors',
            runFn: this.&runMonitors,
            interval: {monitorInterval},
            delay: startupDelay,
            primaryOnly: true
        )
    }

    /**
     * Get the current set of aggregated results for all configured monitors.
     *
     * Results will be sorted according to sort orders defined in the monitor configurations and
     * will include stub entries for inactive monitors.
     */
    @ReadOnly
    List<AggregateMonitorResult> getResults() {
        def results = _results.get()
        Monitor
            .listOrderBySortOrder()
            .collect {
                def ret = it.active && results ? results[it.code] : null
                return ret ?: emptyResults(it)
            }
    }

    /**
     * Force all status monitors to be run on all cluster instances at the next opportunity.
     * Will run monitors even when in development mode or with monitoring disabled via config.
     */
    void forceRun() {
        if (isPrimary) timer.forceRun()
    }


    //------------------
    // Implementation
    //------------------
    private void runMonitors() {
        // Gather per-instance results from across the cluster
        Map<String, List<MonitorResult>> newChecks = clusterService
            .submitToAllInstances(new RunAllMonitorsTask())
            .collectMany { instance, response -> (response.value ?: []) }
            .groupBy { it.code }

        // Merge with existing results and save
        Map<String, AggregateMonitorResult> prevResults = _results.get()
        Map<String, AggregateMonitorResult> newResults = newChecks.collectEntries { code, checks ->
            [code, newResults(checks, prevResults?[code])]
        }
        _results.set(newResults)

        // Report the canonical results from public getter
        monitorReportService.noteResultsUpdated(results)
    }

    @CompileDynamic
    static class RunAllMonitorsTask extends ClusterRequest<List<MonitorResult>> {
        List<MonitorResult> doCall() {
            return appContext.monitorEvalService.runAllMonitors()
        }
    }

    private Integer getMonitorInterval() {
        return isDevelopmentMode() || !configService.getBool('xhEnableMonitoring')
            ? -1
            : config.monitorRefreshMins * MINUTES
    }

    private Integer getStartupDelay() {
        return config.monitorStartupDelayMins * MINUTES
    }

    private MonitorConfig getConfig() {
        new MonitorConfig(configService.getMap('xhMonitorConfig'))
    }

    void clearCaches() {
        super.clearCaches()
        if (isPrimary) {
            _results.set(null)
            if (monitorInterval > 0) {
                timer.forceRun()
            }
        }
    }

    Map getAdminStats() {
        [
            config: configForAdminStats('xhMonitoringEnabled', 'xhMonitorConfig'),
            results: _results.get()?.collectEntries { code, results -> [code, results.formatForJSON()] }
        ]
    }
}
