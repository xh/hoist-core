/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2024 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.monitor

import groovy.transform.CompileStatic
import io.xh.hoist.json.JSONFormat

import static io.xh.hoist.monitor.MonitorStatus.UNKNOWN

@CompileStatic
class MonitorInfo implements JSONFormat {
    Monitor monitor
    StatusInfo statusInfo
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
            metricUnit: monitor.metricUnit,
            statusInfo: statusInfo,
            instanceResults: instanceResults
        ]
    }

}
