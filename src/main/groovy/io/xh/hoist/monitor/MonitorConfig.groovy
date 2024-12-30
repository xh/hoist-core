/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2025 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.monitor

import groovy.transform.MapConstructor

/**
 * Typed representation of `xhMonitorConfig` values.
 */
@MapConstructor
class MonitorConfig {
    Integer monitorRefreshMins
    Integer monitorStartupDelayMins
    Integer monitorRepeatNotifyMins
    Integer failNotifyThreshold
    Integer warnNotifyThreshold
    Integer monitorTimeoutSecs
    Boolean writeToMonitorLog
}
