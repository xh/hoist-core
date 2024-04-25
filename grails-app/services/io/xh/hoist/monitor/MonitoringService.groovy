/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.monitor

import grails.gorm.transactions.ReadOnly
import io.xh.hoist.BaseService
import io.xh.hoist.cluster.ClusterRequest
import io.xh.hoist.cluster.ReplicatedValue
import io.xh.hoist.util.Timer

import static io.xh.hoist.monitor.MonitorResults.emptyResults
import static io.xh.hoist.monitor.MonitorResults.newResults

import static io.xh.hoist.util.DateTimeUtils.MINUTES
import static grails.util.Environment.isDevelopmentMode
import static io.xh.hoist.util.Utils.getAppContext


/**
 * Coordinates application status monitoring. The primary instance will co-ordinate
 * monitor results on cluster and make them globally available.
 *
 * In local development mode, auto-run/refresh of Monitors is disabled, but monitors can still be
 * run on demand via forceRun(). Notification are never sent during local development.
 *
 * If enabled via config, this service will also write monitor run results to a dedicated log file.
 */
class MonitoringService extends BaseService {

    def configService,
        monitoringReportService

    // Shared state for all servers to read -- gathered by primary from all instances
    private ReplicatedValue<Map<String, MonitorResults>> _results = getReplicatedValue('results')

    private Timer timer

    void init() {
        timer = createTimer(
            interval: {monitorInterval},
            delay: startupDelay,
            primaryOnly: true
        )
    }

    void forceRun() {
        timer.forceRun()
    }

    @ReadOnly
    List<MonitorResults> getResults() {
        def results = _results.get()
        Monitor.list().collect { results?[it.code] ?: emptyResults(it) }
    }


    //--------------------------------------------------------------------
    // Implementation
    //--------------------------------------------------------------------
    private void onTimer() {
        Map<String, List<MonitorResult>> newChecks = clusterService
            .submitToAllInstances(new RunAllMonitorsTask())
            .collectMany { instance, response -> (response.value ?: [])}
            .groupBy { it.code }

        Map<String, MonitorResults> prevResults = _results.get()
        Map<String, MonitorResults> newResults = newChecks.collectEntries { code, checks ->
            [code, newResults(checks, prevResults?[code])]
        }
        _results.set(newResults)

        monitoringReportService.noteResultsUpdated(newResults.values())
    }

    static class RunAllMonitorsTask extends ClusterRequest<List<MonitorResult>> {
        List<MonitorResult> doCall() {
            return appContext.monitorResultService.runAllMonitors()
        }
    }

    private int getMonitorInterval() {
        return isDevelopmentMode() || !configService.getBool('xhEnableMonitoring') ? -1 : (monitorConfig.monitorRefreshMins * MINUTES)
    }

    private int getStartupDelay() {
        return monitorConfig.monitorStartupDelayMins * MINUTES
    }

    private Map getMonitorConfig() {
        configService.getMap('xhMonitorConfig')
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

    Map getAdminStats() {[
        config: configForAdminStats('xhMonitoringEnabled', 'xhMonitorConfig'),
    ]}
}