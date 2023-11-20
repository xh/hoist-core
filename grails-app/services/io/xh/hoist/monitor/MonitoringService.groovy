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
import io.xh.hoist.cluster.ReplicatedValue
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
    private ReplicatedValue<Map<String, MonitorResult>> _results = hzReplicatedValue('results')

    // Notification state for master to read only
    private ReplicatedValue<Map<String, Map>> problems = hzReplicatedValue('problems')
    private ReplicatedValue<Boolean> alertMode = hzReplicatedValue('alertMode')
    private ReplicatedValue<Long> lastNotified = hzReplicatedValue('lastNotified')

    private Timer monitorTimer
    private Timer notifyTimer

    void init() {
        monitorTimer = createTimer(
                masterOnly: true,
                name: 'monitorTimer',
                runFn: this.&onMonitorTimer,
                interval: {monitorInterval},
                delay: startupDelay
        )

        notifyTimer = createTimer (
                masterOnly: true,
                name: 'notifyTimer',
                runFn: this.&onNotifyTimer,
                interval: {notifyInterval}
        )
    }

    void forceRun() {
        monitorTimer.forceRun()
    }

    @ReadOnly
    Map<String, MonitorResult> getResults() {
        def results = _results.get()
        Monitor.list().collectEntries {
            [it.code, results?[it.code] ?: monitorResultService.unknownMonitorResult(it)]
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

            Map<String, MonitorResult> newResults = Promises
                    .waitAll(tasks)
                    .collectEntries{ [it.code, it] }

            markLastStatus(newResults, _results.get())
            _results.set(newResults)
            if (monitorConfig.writeToMonitorLog != false) logResults()
            evaluateProblems()
        }
    }

    private void markLastStatus(Map<String, MonitorResult> newResults, Map<String, MonitorResult> oldResults) {
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
        Map<String, MonitorResult> flaggedResults = results.findAll { it.value.status >= WARN }

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

    Map getAdminStats() {[
        config: configService.getForAdminStats('xhMonitoringEnabled', 'xhMonitorConfig'),
    ]}
}