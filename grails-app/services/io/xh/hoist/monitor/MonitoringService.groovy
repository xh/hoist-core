/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.monitor

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
    private ReplicatedValue<Map<String, Map<String, MonitorResult>>> _results = getReplicatedValue('results')
    private ReplicatedValue<Map<String, MonitorStatus>> _statuses = getReplicatedValue('statuses')
    private ReplicatedValue<Map<String, Date>> _lastStatusChange = getReplicatedValue('lastStatusChange')

    // Notification state for master to read only
    private ReplicatedValue<Map<String, Map>> problems = getReplicatedValue('problems')
    private ReplicatedValue<Boolean> alertMode = getReplicatedValue('alertMode')
    private ReplicatedValue<Long> lastNotified = getReplicatedValue('lastNotified')

    private Timer monitorTimer
    private Timer notifyTimer
    private Timer cleanupTimer

    void init() {
        monitorTimer = createTimer(
                masterOnly: false,
                name: 'monitorTimer',
                runFn: this.&onMonitorTimer,
                interval: {monitorInterval},
                delay: startupDelay
        )

        notifyTimer = createTimer (
                masterOnly: false,
                name: 'notifyTimer',
                runFn: this.&onNotifyTimer,
                interval: {notifyInterval}
        )

        cleanupTimer = createTimer(
            masterOnly: true,
            name: 'cleanupTimer',
            runFn: this.&cleanup,
            interval: {cleanupInterval}
        )
    }

    void forceRun() {
        monitorTimer.forceRun()
    }

    @ReadOnly
    List<Map<String, Object>> getResults() {
        def results = _results.get()
        Monitor.list().collect {
            def code = it.code
            def monitorStatusHistory = statusHistory.find{it.code == code}
            [code: code, name: it.name, sortOrder: it.sortOrder, masterOnly: it.masterOnly, status: monitorStatusHistory.status, lastStatusChange: monitorStatusHistory.lastStatusChange,  results: results?.findAll{it.value.containsKey(code)}?.collect{[server: it.key, result: it.value.get(code)]} ?: [[server: Utils.clusterService.masterName, result: monitorResultService.unknownMonitorResult(it)]]]
        }
    }

    @ReadOnly
    List<Map<String, Object>> getStatusHistory() {
        def statuses = _statuses.get()
        def lastStatusChange = _lastStatusChange.get()
        Monitor.list().collect {
            [code: it.code, status: statuses?.get(it.code) ?: UNKNOWN, lastStatusChange: lastStatusChange?.get(it.code) ?: new Date()]
        }
    }

    @ReadOnly
    Map<String, MonitorStatus> getStatuses() {
        return _statuses.get()
    }

    @ReadOnly
    Map<String, Date> getLastStatusChange() {
        return _lastStatusChange.get()
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


            Map<String, Map<String, MonitorResult>> newResultsMap = newResults
                    .collectEntries{k, v -> [(localName): [(k): v]]}

            markLastStatus(newResults, _results.get()?[localName])
            _results.get()? _results.get()[localName] = newResults : _results.set(newResultsMap)
            Utils.clusterService.submitToInstance(new StatusUpdater(), Utils.clusterService.masterName)
            if (monitorConfig.writeToMonitorLog != false) logResults()
            evaluateProblems()
        }
    }

    @ReadOnly
    private void updateStatuses() {
        for (Monitor monitor : Monitor.list()) {
            List<MonitorStatus> monitorStatuses = results.findAll{it.code == monitor.code}.collect{it.results.result.status}
            MonitorStatus newStatus = monitorStatuses.max()[0]
            if (!_statuses.get()) {
                _statuses.set([(monitor.code): newStatus])
                _lastStatusChange.set([(monitor.code): new Date()])
            } else {
                if (newStatus > _statuses.get()[monitor.code]) {
                    _statuses.get()[monitor.code] = newStatus
                    _lastStatusChange.get()[monitor.code] = new Date()
                }
            }
        }
    }

    static class StatusUpdater extends ClusterRequest {
        def doCall() {
            appContext.monitoringService.updateStatuses()
        }
    }

    private static void markLastStatus(Map<String, MonitorResult> newResults, Map<String, MonitorResult> oldResults) {
        def now = new Date()
        newResults.values().each {result ->
            def oldResult = oldResults?[result.code],
                lastStatus = oldResult ? oldResult.status : UNKNOWN,
                statusChanged = lastStatus != result.status
            result.lastStatus = lastStatus

            result.lastStatusChanged = statusChanged ? now : oldResult.lastStatusChanged
            result.checksInStatus = statusChanged ? 1 : oldResult.checksInStatus + 1
        }
    }

    private void evaluateProblems() {
        Map<String, MonitorResult> flaggedResults = results
            .findAll { it.results.result.status[0] >= WARN }
            .collectEntries{[it.code, it.results.result]}

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
        def results = results.collect{it.results.result}
        new MonitorStatusReport(results: results)
    }

    private void logResults() {
        results.each { it ->
            def code = it.code
            def innerResults = it.results
            innerResults.each { res ->
                logInfo([server: res.server, code: code, status: res.result.status, metric: res.result.metric])
            }
        }

        def failsCount = statusHistory.count {it.status == FAIL},
            warnsCount = statusHistory.count {it.status == WARN},
            okCount = statusHistory.count {it.status == OK}

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
            _results.set(null)
            problems.set(null)
            if (monitorInterval > 0) {
                monitorTimer.forceRun()
            }
        }
    }

    void cleanup() {
        def results = _results.get()
        def clusterService = Utils.clusterService
        for (String server: results.keySet()) {
            if (!clusterService.isMember(server)) {
                results.remove(server)
            }
        }
        _results.set(results)
    }

    Map getAdminStats() {[
        config: configForAdminStats('xhMonitoringEnabled', 'xhMonitorConfig'),
    ]}
}