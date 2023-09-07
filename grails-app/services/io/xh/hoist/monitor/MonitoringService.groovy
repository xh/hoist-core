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
import io.xh.hoist.util.Timer

import static grails.async.Promises.task
import static io.xh.hoist.monitor.MonitorStatus.*
import static io.xh.hoist.util.DateTimeUtils.MINUTES
import static io.xh.hoist.util.DateTimeUtils.SECONDS
import static grails.util.Environment.isDevelopmentMode
import static java.lang.System.currentTimeMillis

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
    private Map<String, MonitorResult> _results

    // Notification state for master to read only
    private Map<String, Map> _problems
    private Map<String, Serializable> _notifyState

    private Timer _monitorTimer
    private Timer _notifyTimer

    void init() {
        _results = clusterService.getReplicatedMap('xhMonitorResults')
        _problems = clusterService.getMap('xhMonitorProblems')
        _notifyState = clusterService.getMap('xhMonitorNotifyState')

        _monitorTimer = createTimer(
                interval: {monitorInterval},
                delay: startupDelay,
                runFn: this.&onMonitorTimer,
                masterOnly: true
        )

        _notifyTimer = createTimer (
                interval: {notifyInterval},
                runFn: this.&onNotifyTimer,
                masterOnly: true
        )
    }

    void forceRun() {
        _monitorTimer.forceRun()
    }

    @ReadOnly
    Map<String, MonitorResult> getResults() {
        Monitor.list().collectEntries {
            def result = _results[it.code] ?: monitorResultService.unknownMonitorResult(it)
            [it.code, result]
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

            Map newResults = Promises
                    .waitAll(tasks)
                    .collectEntries{ [it.code, it] }

            markLastStatus(newResults, _results)
            _results.clear()
            _results.putAll(newResults)
            if (monitorConfig.writeToMonitorLog != false) logResults()
            evaluateProblems()
        }
    }

    private void markLastStatus(Map<String, MonitorResult> newResults, Map<String, MonitorResult> oldResults) {
        def now = new Date()
        newResults.values().each {result ->
            def oldResult = oldResults[result.code],
                lastStatus = oldResult ? oldResult.status : UNKNOWN,
                statusChanged = lastStatus != result.status

            result.lastStatus = lastStatus

            result.lastStatusChanged = statusChanged ? now : oldResult.lastStatusChanged
            result.checksInStatus = statusChanged ? 1 : oldResult.checksInStatus + 1
        }
    }

    private void evaluateProblems() {
        Map<String, MonitorResult> flaggedResults = results.findAll { it.value.status >= WARN }

        // 0) Remove all problems that are no longer problems
        def removes = _problems.keySet().findAll { !flaggedResults[it] }
        removes.each { _problems.remove(it) }

        // 1) (Re)Mark all existing problems
        flaggedResults.each { code, result ->
            def problem = _problems[code]
            if (!problem) {
                problem = _problems[code] = [result: result, cyclesAsFail: 0, cyclesAsWarn: 0]
            }

            if (result.status == FAIL) {
                problem.cyclesAsFail++
            } else {
                problem.cyclesAsFail = 0
                problem.cyclesAsWarn++
            }
        }

        // 2) Handle alert mode transition -- notify immediately
        // Note that we may get an extra transition if new master introduced in alerting
        def currAlertMode = calcAlertMode()
        if (currAlertMode != _notifyState['alertMode']) {
            _notifyState['alertMode'] = currAlertMode
            notifyAlertModeChange()
        }
    }

    private void notifyAlertModeChange() {
        if (!isDevelopmentMode()) {
            getTopic('xhMonitorStatusReport').publishAsync(generateStatusReport())
            _notifyState['lastNotified'] = currentTimeMillis()
        }
    }

    private boolean calcAlertMode() {
        if (_notifyState['alertMode'] && _problems) return true

        def failThreshold = monitorConfig.failNotifyThreshold,
            warnThreshold = monitorConfig.warnNotifyThreshold

        return _problems.values().any {
            it.cyclesAsFail >= failThreshold || it.cyclesAsWarn >= warnThreshold
        }
    }

    private MonitorStatusReport generateStatusReport() {
        def results = results.values().toList()
        new MonitorStatusReport(results: results)
    }

    private void logResults() {
        results.each { code, result ->
            def status = result.status,
                metric = result.metric

            logInfo([monitorCode: code, status: status, metric: metric])
        }

        def failsCount = results.count {it.value.status == FAIL},
            warnsCount = results.count {it.value.status == WARN},
            okCount = results.count {it.value.status == OK}

        logInfo([fails: failsCount, warns: warnsCount, okays: okCount])
    }

    private void onNotifyTimer() {
        def lastNotified = _notifyState['lastNotified'],
            alertMode = _notifyState['alertMode']
        if (!alertMode || !lastNotified) return
        def now = currentTimeMillis(),
            timeThresholdMet = now > lastNotified + monitorConfig.monitorRepeatNotifyMins * MINUTES

        if (timeThresholdMet) {
            def report = generateStatusReport()
            logDebug("Emitting monitor status report: ${report.title}")
            getTopic('xhMonitorStatusReport').publishAsync(report)
            _notifyState['lastNotified'] = currentTimeMillis()
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
            _problems.clear()
            if (monitorInterval > 0) {
                _monitorTimer.forceRun()
            }
        }
    }
}