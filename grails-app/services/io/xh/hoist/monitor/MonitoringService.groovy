/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2018 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.monitor

import grails.async.Promises
import grails.events.EventPublisher
import io.xh.hoist.BaseService
import io.xh.hoist.util.Timer
import io.xh.hoist.async.AsyncSupport
import org.grails.web.json.JSONObject

import java.util.concurrent.ConcurrentHashMap

import static io.xh.hoist.monitor.MonitorStatus.*
import static io.xh.hoist.util.DateTimeUtils.MINUTES
import static io.xh.hoist.util.DateTimeUtils.SECONDS
import static grails.util.Environment.isDevelopmentMode
import static java.lang.System.currentTimeMillis

/**
 * Service for application monitoring, governs generation of monitor results and status reports, analysis of these results,
 * and the emission of grails events to notify status conditions that may be of interest to the application.
 *
 * Monitor refresh timer and notification timer are disabled for local development.
 * If alertMode changes via forceRun in local development mode, notification will not be sent.
 */
class MonitoringService extends BaseService implements AsyncSupport, EventPublisher {

    def configService,
        monitorResultService

    private Map<String, MonitorResult> _results = new ConcurrentHashMap()
    private Map<String, Map> _problems = new ConcurrentHashMap()
    private Timer _monitorTimer
    private Timer _notifyTimer
    private boolean _alertMode = false
    private Long _lastNotified

    void init() {
        def monitorInterval = isDevelopmentMode() ? -1 : {monitorConfig.monitorRefreshMins}
        _monitorTimer = createTimer(
                interval: monitorInterval,
                intervalUnits: MINUTES,
                delay: {monitorConfig.monitorStartupDelayMins},
                delayUnits: MINUTES,
                runFn: this.&onMonitorTimer
        )

        def notifyInterval = isDevelopmentMode() ? -1 : 15
        _notifyTimer = createTimer (
                interval: notifyInterval * SECONDS,
                runFn: this.&onNotifyTimer
        )
        super.init()
    }

    void forceRun() {
        _monitorTimer.forceRun()
    }

    Map<String, MonitorResult> getResults() {
        Monitor.list().collectEntries {
            def result = _results[it.code] ?: monitorResultService.unknownMonitorResult(it)
            [it.code, result]
        }
    }


    //------------------------
    // Implementation
    //------------------------
    private void runAllMonitors() {
        withDebug('Running monitors') {
            def tasks = Monitor.list().collect { m ->
                asyncTask { monitorResultService.runMonitor(m) }
            }

            Map newResults = Promises
                    .waitAll(tasks)
                    .collectEntries(new ConcurrentHashMap()) { [it.code, it] }

            markLastStatus(newResults, _results)
            _results = newResults
            if (monitorConfig.writeToMonitorLog) logResults()
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
        Map<String, MonitorResult> flaggedResults = results.findAll {it.value.status >= WARN}

        // 0) Remove all problems that are no longer problems
        def removes = _problems.keySet().findAll {!flaggedResults[it]}
        removes.each {_problems.remove(it)}

        // 1) (Re)Mark all existing problems
        flaggedResults.each {code, result ->
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
        def currAlertMode = calcAlertMode()
        if (currAlertMode != _alertMode) {
            _alertMode = currAlertMode
            notifyAlertModeChange()
        }
    }

    private void notifyAlertModeChange() {
        if(!isDevelopmentMode()) {
            notify('xhMonitorStatusReport', generateStatusReport())
            _lastNotified = currentTimeMillis()
        }
    }

    private boolean calcAlertMode() {
        if (_alertMode && _problems) return true

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
        results.each{code, result ->
            def status = result.status,
                metric = result.metric

            log.info("monitorCode=${code} | status=${status} | metric=${metric}")
        }

        def failsCount = results.count{it.value.status == FAIL},
            warnsCount = results.count{it.value.status == WARN},
            okCount = results.count{it.value.status == OK}

        log.info("fails=${failsCount} | warns=${warnsCount} | okays=${okCount}")
    }

    private void onNotifyTimer() {
        if (!_alertMode || !_lastNotified) return
        def now = currentTimeMillis(),
            timeThresholdMet = now > _lastNotified + monitorConfig.monitorRepeatNotifyMins * MINUTES

        if (timeThresholdMet) {
            def report = generateStatusReport()
            log.debug("Emitting monitor status report: ${report.title}")
            notify('xhMonitorStatusReport', report)
            _lastNotified = now
        }
    }

    private void onMonitorTimer() {
        runAllMonitors()
    }

    private JSONObject getMonitorConfig() {
        configService.getJSONObject('xhMonitorConfig')
    }

    void clearCaches() {
        super.clearCaches()
        _results.clear()
        _problems.clear()
        _monitorTimer.forceRun()
    }
    
}
