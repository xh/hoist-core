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
import static grails.async.Promises.task

import java.util.concurrent.TimeoutException

import static io.xh.hoist.monitor.MonitorStatus.*
import static java.util.concurrent.TimeUnit.SECONDS


class MonitorResultService extends BaseService {

    def configService,
        monitorDefinitionService

    /**
     * Runs all enabled and active monitors on this instance in parallel.
     */
    @ReadOnly
    List<MonitorResult> runAllMonitors() {
        def timeout = getTimeoutSeconds(),
            monitors = Monitor.list().findAll{it.active && (isPrimary || !it.primaryOnly)}

        withDebug("Running ${monitors.size()} monitors") {
            def tasks = monitors.collect { m -> task {runMonitor(m, timeout)}},
                ret = Promises.waitAll(tasks)

            if (monitorConfig.writeToMonitorLog != false) logResults(ret)

            return ret
        } as List<MonitorResult>
    }

    /**
     * Runs individual monitor on this instance. Timeouts and any other exceptions will be
     * caught and returned cleanly as failures.
     */
    MonitorResult runMonitor(Monitor monitor, long timeoutSeconds) {
        def defSvc = monitorDefinitionService,
            code = monitor.code,
            result = new MonitorResult(monitor: monitor, instance: clusterService.localName, primary: isPrimary),
            startTime = new Date()

        try {
            if (!defSvc?.metaClass?.respondsTo(defSvc, code)) {
                throw new RuntimeException("Monitor '$code' not implemented by this application's MonitorDefinitionService.")
            }

            // Run the check...
            task {
                defSvc."$code"(result)
            }.get(timeoutSeconds, SECONDS)

            // Default status to OK if it has not already been set within the check.
            if (result.status == UNKNOWN) {
                result.status = OK
            }

            // If a check has been marked as inactive or failed, skip any metric-based evaluation -
            // the check implementation has already decided this should not run or is failing.
            // Otherwise, eval metrics to confirm a final status.
            if (result.status != INACTIVE && result.status != FAIL) {
                evaluateThresholds(monitor, result)
            }
        } catch (Exception e) {
            result.prependMessage(e instanceof TimeoutException ?
                                    "Monitor run timed out after $timeoutSeconds seconds." :
                                    e.message ?: e.class.name
                                 )
            result.status = FAIL
            result.exception = e
        } finally {
            def endTime = new Date()
            result.date = endTime
            result.elapsed = endTime.time - startTime.time
        }

        return result
    }

    //------------------------
    // Implementation
    //------------------------
    private static evaluateThresholds(Monitor monitor, MonitorResult result) {
        def type = monitor.metricType,
            metric = result.metric

        if (type == 'None') return

        if (metric == null) {
            result.status = FAIL
            result.prependMessage("Monitor failed to compute metric")
            return
        }

        def isCeil = (type == 'Ceil'),
            sign = isCeil ? 1 : -1,
            verb = isCeil ? 'above' : 'below',
            fail =  monitor.failThreshold,
            warn = monitor.warnThreshold,
            currSeverity = result.status.severity,
            units = monitor.metricUnit ?: ''

        if (fail != null && (metric - fail) * sign > 0 && currSeverity < FAIL.severity) {
            result.status = FAIL
            result.prependMessage("Metric value is $verb failure limit of $fail $units")
        } else if (warn != null && (metric - warn) * sign > 0 && currSeverity < WARN.severity) {
            result.status = WARN
            result.prependMessage("Metric value is $verb warn limit of $warn $units")
        }
    }

    //---------------------
    // Implementation
    //--------------------
    private long getTimeoutSeconds() {
        (monitorConfig.monitorTimeoutSecs ?: 15) as long
    }

    private Map getMonitorConfig() {
        configService.getMap('xhMonitorConfig')
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
}
