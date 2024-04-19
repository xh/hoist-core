/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2024 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.monitor

import io.xh.hoist.util.Utils

import static io.xh.hoist.monitor.MonitorStatus.*


class StatusInfo {
    MonitorStatus status = UNKNOWN
    Date lastChange
    Integer cyclesAsSuccess = 0
    Integer cyclesAsFail = 0
    Integer cyclesAsWarn = 0

    void recordStatus(MonitorStatus status) {
        // Keep track of the number of consecutive cycles in each status
        switch (status) {
            case FAIL:
                // Entering FAIL does not clear WARN streaks
                cyclesAsSuccess = 0
                cyclesAsFail++
                break
            case WARN:
                cyclesAsSuccess = 0
                cyclesAsFail = 0
                cyclesAsWarn++
                break
            case OK:
                cyclesAsFail = 0
                cyclesAsWarn = 0
                cyclesAsSuccess++
                break
        }
        if (status != this.status) {
            this.status = status
            lastChange = new Date()
        }
    }
}
