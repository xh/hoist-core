/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.monitor

import com.hazelcast.core.EntryListener
import com.hazelcast.map.IMap
import grails.async.Promises
import grails.gorm.transactions.ReadOnly
import io.xh.hoist.BaseService
import io.xh.hoist.cluster.ReplicatedValue
import io.xh.hoist.util.Timer
import io.xh.hoist.util.Utils

import static io.xh.hoist.util.DateTimeUtils.intervalElapsed
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
    private IMap<String, Map<String, MonitorResult>> _results = getIMap('results')
    private ReplicatedValue<Map<String, StatusInfo>> _statusInfos = getReplicatedValue('statusInfos')

    // Notification state for master to read only
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
            interval: {notifyInterval},
            masterOnly: true
        )

        cleanupTimer = createTimer(
            name: 'cleanupTimer',
            runFn: this.&cleanup,
            interval: {cleanupInterval},
            masterOnly: true,
        )

        _results.addEntryListener([
            entryAdded: { updateStatuses() },
            entryUpdated: { updateStatuses() },
            entryRemoved: { updateStatuses() }
        ] as EntryListener, false)
    }

    void forceRun() {
        cleanupTimer.forceRun()
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
                statusInfo: statusInfo,
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

            def clusterService = Utils.clusterService,
                localName = clusterService.localName

           Map<String, MonitorResult> newResults = Promises
                    .waitAll(tasks)
                    .collectEntries {
                        it.instance = clusterService.isMaster ? localName + '-M' : localName
                        [it.code, it]
                    }

            _results[localName] = newResults
            if (monitorConfig.writeToMonitorLog != false) logResults(newResults.values())
        }
    }

    @ReadOnly
    private void updateStatuses() {
        if (!isMaster) return
        def statusInfos = _statusInfos.get() ?: [:]
        Monitor.list().each{ monitor ->
            def code = monitor.code
            List<MonitorStatus> statuses = _results.findAll{it.value[code]}.collect{it.value[code].status}
            def statusInfo = statusInfos[code] ?: new StatusInfo()
            statusInfo.recordStatus(statuses.max())
            statusInfos[code] = statusInfo
        }
        _statusInfos.set(statusInfos)
        evaluateProblems()
    }

    private void evaluateProblems() {
        def statusInfos = _statusInfos.get()?.values() ?: [] as Collection<StatusInfo>,
            failThreshold = monitorConfig.failNotifyThreshold,
            warnThreshold = monitorConfig.warnNotifyThreshold

        // Calc new alert mode, true if crossed thresholds or already alerting and still have problems
        def currAlertMode = alertMode.get()
        def newAlertMode = (currAlertMode && statusInfos.any { it.status >= WARN }) ||
            statusInfos.any { it.cyclesAsFail >= failThreshold || it.cyclesAsWarn >= warnThreshold }
        if (newAlertMode != alertMode.get()) {
            alertMode.set(newAlertMode)
            notifyAlertModeChange()
        }
    }

    private void notifyAlertModeChange() {
        if (!isDevelopmentMode()) {
            getTopic('xhMonitorStatusReport').publishAsync(generateStatusReport())
            lastNotified.set(currentTimeMillis())
        }
    }

    private MonitorStatusReport generateStatusReport() {
        new MonitorStatusReport(infos: results)
    }

    private void logResults(Collection<MonitorResult> results) {
        results.each {
            logInfo([code: it.code, status: it.status, metric: it.metric])
        }

        def failsCount = results.count {it.status == FAIL},
            warnsCount = results.count {it.status == WARN},
            okCount = results.count {it.status == OK}

        logInfo([fails: failsCount, warns: warnsCount, okays: okCount])
    }

    private void onNotifyTimer() {
        if (!alertMode.get()) return

        if (intervalElapsed(monitorConfig.monitorRepeatNotifyMins * MINUTES, lastNotified.get())) {
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

    private void cleanup() {
        for (String instance: _results.keySet()) {
            if (!clusterService.isMember(instance)) {
                _results.remove(instance)
            }
        }
    }

    void clearCaches() {
        super.clearCaches()
        if (isMaster) {
            _results.clear()
            _statusInfos.set(null)
            if (monitorInterval > 0) {
                monitorTimer.forceRun()
            }
        }
    }

    Map getAdminStats() {[
        config: configForAdminStats('xhMonitoringEnabled', 'xhMonitorConfig'),
    ]}
}