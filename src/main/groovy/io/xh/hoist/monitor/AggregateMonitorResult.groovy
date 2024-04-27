/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2024 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.monitor

import groovy.transform.CompileStatic
import io.xh.hoist.json.JSONFormat

import static io.xh.hoist.monitor.MonitorStatus.FAIL
import static io.xh.hoist.monitor.MonitorStatus.INACTIVE
import static io.xh.hoist.monitor.MonitorStatus.OK
import static io.xh.hoist.monitor.MonitorStatus.UNKNOWN
import static io.xh.hoist.monitor.MonitorStatus.WARN
import static io.xh.hoist.util.DateTimeUtils.MINUTES
import static java.lang.System.currentTimeMillis

/**
 * Consolidated results for a single monitor, linking together individual latest results from
 * multiple instances to produce a consolidated overall status based on the worst result.
 *
 * Also tracks history of cycle changes at a high level, reporting on the number of check cycles for
 * which the monitor has been in a given status.
 */
@CompileStatic
class AggregateMonitorResult implements JSONFormat {
    Monitor monitor
    MonitorStatus status
    List<MonitorResult> results
    Date dateComputed

    // Aggregated history
    int cyclesAsSuccess = 0
    int cyclesAsFail = 0
    int cyclesAsWarn = 0
    Date lastStatusChanged

    String getName() {
        return monitor.name
    }

    String getMessage() {
        return status > OK ? results.find { it.status == status }.message : ''
    }

    String getMinsInStatus() {
        def now = currentTimeMillis(),
            timeInStatus = now - lastStatusChanged.time

        (timeInStatus / MINUTES).intValue()
    }

    /**
     * Create an empty result, for when no underlying checks are available.
     */
    static AggregateMonitorResult emptyResults(Monitor monitor) {
        new AggregateMonitorResult(monitor)
    }

    /**
     * Create a new result, carrying forward existing status streaks (cycle counts) if available.
     */
    static AggregateMonitorResult newResults(List<MonitorResult> results, AggregateMonitorResult prev) {
        def ret = new AggregateMonitorResult(results[0].monitor, results)

        // If there is history, bring it over and append to it.
        if (prev && ret.status != INACTIVE && ret.status != UNKNOWN) {
            ret.cyclesAsSuccess = prev.cyclesAsSuccess
            ret.cyclesAsFail = prev.cyclesAsFail
            ret.cyclesAsWarn = prev.cyclesAsWarn
            ret.lastStatusChanged = ret.status == prev.status ? prev.lastStatusChanged : new Date()

            switch (ret.status) {
                case FAIL:
                    // Entering FAIL does not clear WARN streaks
                    ret.cyclesAsSuccess = 0
                    ret.cyclesAsFail++
                    break
                case WARN:
                    ret.cyclesAsSuccess = 0
                    ret.cyclesAsFail = 0
                    ret.cyclesAsWarn++
                    break
                case OK:
                    ret.cyclesAsFail = 0
                    ret.cyclesAsWarn = 0
                    ret.cyclesAsSuccess++
                    break
            }
        }

        return ret
    }

    //------------------
    // Implementation
    //------------------
    private AggregateMonitorResult(Monitor monitor, List<MonitorResult> results = null) {
        this.monitor = monitor
        this.results = results
        status = results ? results*.status.max() : monitor.active ? UNKNOWN : INACTIVE
        dateComputed = lastStatusChanged = new Date()
    }

    Map formatForJSON() {[
        code: monitor.code,
        name: monitor.name,
        sortOrder: monitor.sortOrder,
        primaryOnly: monitor.primaryOnly,
        metricUnit: monitor.metricUnit,

        status: status,
        results: results,
        dateComputed: dateComputed,

        cyclesAsSuccess: cyclesAsSuccess,
        cyclesAsFail: cyclesAsFail,
        cyclesAsWarn: cyclesAsWarn,
        lastStatusChanged: lastStatusChanged
    ]}
}
