/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.monitor

import groovy.transform.CompileStatic
import io.xh.hoist.json.JSONFormat
import io.xh.hoist.json.JSONParser

import static io.xh.hoist.monitor.MonitorStatus.UNKNOWN

/**
 * Results from the run of a single monitor on a single instance.
 *
 * An instance of this object is passed into each app-level monitor implementation within
 * `MonitorDefinitionService` and is used to collect and return the results of each check.
 */
@CompileStatic
class MonitorResult implements JSONFormat {
    String instance
    Boolean primary
    MonitorStatus status = UNKNOWN
    Object metric
    String message
    Long elapsed
    Date date
    String exception
    Monitor monitor

    String getCode() {
        monitor.code
    }

    Map getParams() {
        monitor.params ? JSONParser.parseObject(monitor.params) : [:]
    }

    /** Combines the given string with 'message', separated by formatting */
    void prependMessage(String prependStr) {
        // Space character before the newlines is for fallback formatting in `hoist-react <= v51.0.0`
        message = prependStr + (message ? " \n\n$message" : '')
    }

    Map formatForJSON() {
        [
            instance: instance,
            primary: primary,
            status: status,
            metric: metric,
            message: message,
            elapsed: elapsed,
            date: date,
            exception: exception
        ]
    }

}
