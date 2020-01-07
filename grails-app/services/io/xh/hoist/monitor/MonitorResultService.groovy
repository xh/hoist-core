/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2019 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.monitor

import grails.gorm.transactions.Transactional
import io.xh.hoist.BaseService
import io.xh.hoist.util.Utils
import io.xh.hoist.async.AsyncSupport

import java.util.concurrent.TimeoutException

import static io.xh.hoist.monitor.MonitorStatus.FAIL
import static io.xh.hoist.monitor.MonitorStatus.INACTIVE
import static io.xh.hoist.monitor.MonitorStatus.OK
import static io.xh.hoist.monitor.MonitorStatus.UNKNOWN
import static io.xh.hoist.monitor.MonitorStatus.WARN

import static java.util.concurrent.TimeUnit.SECONDS

class MonitorResultService extends BaseService implements AsyncSupport {

    private static Long MAX_RUNTIME_SECS = 15

    @Transactional
    MonitorResult runMonitor(String code) {
        def monitor = Monitor.findByCode(code)
        if (!monitor) throw new RuntimeException("No Monitor is defined with code: $code")
        return runMonitor(monitor)
    }

    MonitorResult runMonitor(Monitor monitor) {
        if (!monitor.active) {
            return inactiveMonitorResult(monitor)
        }

        def defSvc = Utils.appContext.monitorDefinitionService,
            code = monitor.code,
            result = new MonitorResult(monitor: monitor),
            startTime = new Date()

        try {
            if (!defSvc?.metaClass?.respondsTo(defSvc, code)) {
                throw new RuntimeException("Unable to find definition for monitor '$code' in this application.")
            }

            // Run the check...
            asyncTask {
                defSvc."$code"(result)
            }.get(MAX_RUNTIME_SECS, SECONDS)

            // Default status to OK if it has not already been set within the check.
            if (result.status == UNKNOWN) {
                result.status = OK
            }

            // Then (maybe) evaluate thresholds for metric-based checks.
            evaluateThresholds(monitor, result)
        } catch (Exception e) {
            result.message = e instanceof TimeoutException ?
                                "Monitor runtime exceeded max of $MAX_RUNTIME_SECS seconds." :
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
