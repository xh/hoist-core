/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2020 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.monitor

import grails.gorm.transactions.ReadOnly
import io.xh.hoist.BaseService
import io.xh.hoist.util.Utils
import static grails.async.Promises.task

import java.util.concurrent.TimeoutException

import static io.xh.hoist.monitor.MonitorStatus.*
import static java.util.concurrent.TimeUnit.SECONDS


/**
 * Runs individual status monitor checks as directed by MonitorService and as configured by
 * data-driven status monitor definitions. Timeouts and any other exceptions will be caught and
 * returned cleanly as failures.
 */
class MonitorResultService extends BaseService {

    @ReadOnly
    MonitorResult runMonitor(String code, long timeoutSeconds) {
        def monitor = Monitor.findByCode(code)
        if (!monitor) throw new RuntimeException("Monitor '$code' not found.")
        return runMonitor(monitor, timeoutSeconds)
    }

    MonitorResult runMonitor(Monitor monitor, long timeoutSeconds) {
        if (!monitor.active) {
            return inactiveMonitorResult(monitor)
        }

        def defSvc = Utils.appContext.monitorDefinitionService,
            code = monitor.code,
            result = new MonitorResult(monitor: monitor),
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

            // Then (maybe) evaluate thresholds for metric-based checks.
            evaluateThresholds(monitor, result)
        } catch (Exception e) {
            result.message = e instanceof TimeoutException ?
                                "Monitor run timed out after $timeoutSeconds seconds." :
                                e.message ?: e.class.name
            result.status = FAIL
            result.exception = e
        } finally {
            def endTime = new Date()
            result.date = endTime
            result.elapsed = endTime.time - startTime.time
        }

        return result
    }

    MonitorResult unknownMonitorResult(Monitor monitor) {
        return new MonitorResult(
                status: UNKNOWN,
                date: new Date(),
                elapsed: 0,
                monitor: monitor
        )
    }

    MonitorResult inactiveMonitorResult(Monitor monitor) {
        return new MonitorResult(
                status: INACTIVE,
                date: new Date(),
                elapsed: 0,
                monitor: monitor
        )
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
            result.message =  'Monitor failed to compute metric'
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
            result.message = "Metric value is $verb failure limit of $fail $units"
        } else if (warn != null && (metric - warn) * sign > 0 && currSeverity < WARN.severity) {
            result.status = WARN
            result.message = "Metric value is $verb warn limit of $warn $units"
        }
    }

}
