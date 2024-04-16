/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.monitor

import com.hazelcast.map.IMap
import grails.async.Promises
import grails.gorm.transactions.ReadOnly
import io.xh.hoist.BaseService
import io.xh.hoist.cluster.ClusterRequest
import io.xh.hoist.cluster.ReplicatedValue
import io.xh.hoist.util.Timer
import io.xh.hoist.util.Utils

import static grails.async.Promises.task
import static io.xh.hoist.monitor.MonitorStatus.*
import static io.xh.hoist.util.DateTimeUtils.MINUTES
import static io.xh.hoist.util.DateTimeUtils.SECONDS
import static grails.util.Environment.isDevelopmentMode
import static java.lang.System.currentTimeMillis
import static io.xh.hoist.util.Utils.getAppContext


/**
 * Coordinates application status monitoring. Requests monitor results and generates status reports
 * on a configurable timer, analyzes the results, and publishes Grails events on status conditions
 * of interest to the application.
 *
 * In local development mode, auto-run/refresh of Monitors is disabled, but monitors can still be
 * run on demand via forceRun(). Notification are never sent during local development.
 *
 * If enabled via config, this service will also write monitor run results to a dedicated log file.
 */
class MonitoringService extends BaseService {

    def configService,
        monitorResultService

    // Shared state for all servers to read
    private IMap<String, Map<String, MonitorResult>> _results = getIMap('results')
    private ReplicatedValue<Map<String, StatusInfo>> _statusInfos = getReplicatedValue('statusInfos')

    // Notification state for master to read only
    private ReplicatedValue<Map<String, Map>> problems = getReplicatedValue('problems')
    private ReplicatedValue<Boolean> alertMode = getReplicatedValue('alertMode')
    private ReplicatedValue<Long> lastNotified = getReplicatedValue('lastNotified')

    private Timer monitorTimer
    private Timer notifyTimer
    private Timer cleanupTimer

    void init() {
        monitorTimer = createTimer(
                name: 'monitorTimer',
                runFn: this.&onMonitorTimer,
                interval: {monitorInterval},
                delay: startupDelay
        )

        notifyTimer = createTimer (
                name: 'notifyTimer',
                runFn: this.&onNotifyTimer,
                interval: {notifyInterval}
        )

        cleanupTimer = createTimer(
            name: 'cleanupTimer',
            runFn: this.&cleanup,
            interval: {cleanupInterval},
            masterOnly: true,
        )
    }

    void forceRun() {
        monitorTimer.forceRun()
    }

    @ReadOnly
    List<MonitorInfo> getResults() {
        def results = _results,
            statusInfos = _statusInfos.get() ?: [:]
        Monitor.list().collect {
            def code = it.code,
                statusInfo = statusInfos[code] ?: new StatusInfo(status: UNKNOWN),
                instanceResults = results.collect{it.value[code]}

            new MonitorInfo(
                monitor: it,
                status: statusInfo.status,
                lastStatusChange: statusInfo.lastChange,
                instanceResults: instanceResults
            )
        }
    }

    //------------------------
    // Implementation
    //------------------------
    @ReadOnly
    private void runAllMonitors() {
        withDebug('Running monitors') {
            def timeout = getTimeoutSeconds()

            def tasks = Monitor.list().collect { m ->
                task { monitorResultService.runMonitor(m, timeout) }
            }

            def localName = Utils.clusterService.localName

           Map<String, MonitorResult> newResults = Promises
                    .waitAll(tasks)
                    .collectEntries{ [it.code, it] }

            _results[localName] = newResults
            Utils.clusterService.submitToInstance(new StatusUpdater(), Utils.clusterService.masterName)
            if (monitorConfig.writeToMonitorLog != false) logResults()
            evaluateProblems()
        }
    }

    @ReadOnly
    private void updateStatuses() {
        def statusInfos = _statusInfos.get() ?: [:]
        Monitor.list().each{ monitor ->
            def code = monitor.code
            List<MonitorStatus> statuses = _results.findAll{it.value[code]}.collect{it.value[code].status}
            MonitorStatus newStatus = statuses.max()
            if (!statusInfos[code] || newStatus > statusInfos[code].status) {
                statusInfos[code] = new StatusInfo(status: newStatus, lastChange: new Date())
                _statusInfos.set(statusInfos)
            }
        }
    }

    static class StatusUpdater extends ClusterRequest {
        def doCall() {
            appContext.monitoringService.updateStatuses()
        }
    }

    private void evaluateProblems() {
        Map<String, MonitorResult> flaggedResults = results
            .findAll { it.status >= WARN }
            .collectEntries{
                def globalStatus = it.status
                [it.code, it.instanceResults.find { it.status == globalStatus }]
            }

        // 0) Remove all problems that are no longer problems
        def probs = problems.get()?.findAll {flaggedResults[it.key]} ?: [:]

        // 1) (Re)Mark all existing problems
        flaggedResults.each { code, result ->
            def problem = probs[code]
            if (!problem) {
                problem = probs[code] = [result: result, cyclesAsFail: 0, cyclesAsWarn: 0]
            }

            if (result.status == FAIL) {
                problem.cyclesAsFail++
            } else {
                problem.cyclesAsFail = 0
                problem.cyclesAsWarn++
            }
        }
        problems.set(probs)

        // 2) Handle alert mode transition -- notify immediately
        // Note that we may get an extra transition if new master introduced in alerting
        def currAlertMode = calcAlertMode()
        if (currAlertMode != alertMode.get()) {
            alertMode.set(currAlertMode)
            notifyAlertModeChange()
        }
    }

    private void notifyAlertModeChange() {
        if (!isDevelopmentMode()) {
            getTopic('xhMonitorStatusReport').publishAsync(generateStatusReport())
            lastNotified.set(currentTimeMillis())
        }
    }

    private boolean calcAlertMode() {
        if (alertMode.get() && problems.get()) return true

        def failThreshold = monitorConfig.failNotifyThreshold,
            warnThreshold = monitorConfig.warnNotifyThreshold

        return problems.get().values().any {
            it.cyclesAsFail >= failThreshold || it.cyclesAsWarn >= warnThreshold
        }
    }

    private MonitorStatusReport generateStatusReport() {
        def results = results.collectMany{it.instanceResults}
        new MonitorStatusReport(results: results)
    }

    private void logResults() {
        results.each { it ->
            it.instanceResults.each { res ->
                logInfo([instance: res.instance, code: it.code, status: res.status, metric: res.metric])
            }
        }

        def failsCount = results.count {it.status == FAIL},
            warnsCount = results.count {it.status == WARN},
            okCount = results.count {it.status == OK}

        logInfo([fails: failsCount, warns: warnsCount, okays: okCount])
    }

    private void onNotifyTimer() {
        if (!alertMode.get() || !lastNotified.get()) return
        def now = currentTimeMillis(),
            timeThresholdMet = now > lastNotified.get() + monitorConfig.monitorRepeatNotifyMins * MINUTES

        if (timeThresholdMet) {
            def report = generateStatusReport()
            logDebug("Emitting monitor status report: ${report.title}")
            getTopic('xhMonitorStatusReport').publishAsync(report)
            lastNotified.set(currentTimeMillis())
        }
    }

    private void onMonitorTimer() {
        runAllMonitors()
    }

    private int getMonitorInterval() {
        return isDevelopmentMode() || !configService.getBool('xhEnableMonitoring') ? -1 : (monitorConfig.monitorRefreshMins * MINUTES)
    }

    private int getNotifyInterval() {
        return isDevelopmentMode() || !configService.getBool('xhEnableMonitoring') ? -1 : (15 * SECONDS)
    }

    private int getCleanupInterval() {
        return isDevelopmentMode() || !configService.getBool('xhEnableMonitoring') ? -1 : (10 * MINUTES)
    }

    private int getStartupDelay() {
        return monitorConfig.monitorStartupDelayMins * MINUTES
    }

    // Default supplied here as support for this sub-config was added late in the game.
    private long getTimeoutSeconds() {
        return monitorConfig.monitorTimeoutSecs ?: 15
    }

    private Map getMonitorConfig() {
        configService.getMap('xhMonitorConfig')
    }

    void clearCaches() {
        super.clearCaches()
        if (isMaster) {
            _results.clear()
            problems.set(null)
            if (monitorInterval > 0) {
                monitorTimer.forceRun()
            }
        }
    }

    void cleanup() {
        def clusterService = Utils.clusterService
        for (String instance: _results.keySet()) {
            if (!clusterService.isMember(instance)) {
                _results.remove(instance)
            }
        }
    }

    Map getAdminStats() {[
        config: configForAdminStats('xhMonitoringEnabled', 'xhMonitorConfig'),
    ]}
}