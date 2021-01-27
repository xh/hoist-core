/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2021 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.monitor

import groovy.transform.CompileStatic
import io.xh.hoist.json.JSONFormat
import io.xh.hoist.json.JSONParser

import static io.xh.hoist.monitor.MonitorStatus.UNKNOWN
import static io.xh.hoist.util.DateTimeUtils.MINUTES
import static java.lang.System.currentTimeMillis

@CompileStatic
class MonitorResult implements JSONFormat {
    MonitorStatus status = UNKNOWN
    Object metric
    String message
    Long elapsed
    Date date
    Exception exception
    Monitor monitor

    MonitorStatus lastStatus = UNKNOWN
    Date lastStatusChanged
    Integer checksInStatus

    String getName() {
        monitor.name
    }

    String getCode() {
        monitor.code
    }

    Map getParams() {
        monitor.params ? JSONParser.parseObject(monitor.params) : [:]
    }

    String getMinsInStatus () {
        def now = currentTimeMillis(),
            timeInStatus = now - lastStatusChanged.time

            (timeInStatus / MINUTES).intValue()
    }

    Map formatForJSON() {
        [
            code: monitor.code,
            name: monitor.name,
            params: monitor.params,
            notes: monitor.notes,
            sortOrder: monitor.sortOrder,
            metricType: monitor.metricType,
            metricUnit: monitor.metricUnit,
            status: status,
            metric: metric,
            message: message,
            elapsed: elapsed,
            date: date,
            exception: exception?.class?.name,
            lastStatus: lastStatus,
            lastStatusChanged: lastStatusChanged,
            checksInStatus: checksInStatus
        ]
    }
    
}
