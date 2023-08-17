/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.monitor

import io.xh.hoist.util.Utils

import static io.xh.hoist.monitor.MonitorStatus.*

class MonitorStatusReport {
    List<MonitorResult> results

    MonitorStatus getStatus() {
        if (!results) return MonitorStatus.OK
        results.max{it.status}.status
    }

    String getTitle() {
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
