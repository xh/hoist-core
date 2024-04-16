/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.monitor

import groovy.transform.CompileStatic
import io.xh.hoist.json.JSONFormat

import static io.xh.hoist.monitor.MonitorStatus.UNKNOWN

@CompileStatic
class MonitorInfo implements JSONFormat {
    Monitor monitor
    MonitorStatus status = UNKNOWN
    Date lastStatusChange
    List<MonitorResult> instanceResults = []

    String getCode() {
        monitor.code
    }

    Map formatForJSON() {
        [
            code: monitor.code,
            name: monitor.name,
            sortOrder: monitor.sortOrder,
            masterOnly: monitor.masterOnly,
            status: status,
            lastStatusChange: lastStatusChange,
            instanceResults: instanceResults
        ]
    }

}
