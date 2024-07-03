/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.monitor

import io.xh.hoist.util.Utils

import static io.xh.hoist.monitor.MonitorStatus.*

/**
 * Consolidated report of results across all available AggregateMonitorResults, with a single
 * roll-up status and generated title.
 */
class MonitorStatusReport {
    final MonitorStatus status
    final String title
    final List<AggregateMonitorResult> results

    MonitorStatusReport(List<AggregateMonitorResult> results) {
        this.results = results
        status = results ? results.max { it.status }.status : OK
        title = computeTitle()
    }

    String toHtml() {
        def problems = results.findAll {it.status >= WARN}
        if (!problems) return "There are no alerting monitors for ${Utils.appName}."

        return problems
            .sort {it.name}
            .sort {it.status }
            .collect {
                "+ ${it.name} | ${it.message ? it.message + ' | ' : ''}Minutes in [${it.status}]: ${it.minsInStatus}"
            }.join('<br>')
    }

    private String computeTitle() {
        def failsCount = results.count{it.status == FAIL},
            warnsCount = results.count{it.status == WARN},
            okCount = results.count{it.status == OK},
            title = "${Utils.appName}: ",
            msgParts = []

        if (!warnsCount && !failsCount) msgParts.add('All clear')
        if (failsCount) msgParts.add("$failsCount Failures")
        if (warnsCount) msgParts.add("$warnsCount Warnings")
        if (okCount) msgParts.add("$okCount OK")

        title += msgParts.join(' | ')
        title
    }

}
