/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.monitor

import groovy.transform.InheritConstructors
import io.xh.hoist.config.TypedConfigMap

/**
 * Typed representation of `xhMonitorConfig` values.
 */
@InheritConstructors
class MonitorConfig extends TypedConfigMap {
    Integer monitorRefreshMins
    Integer monitorStartupDelayMins
    Integer monitorRepeatNotifyMins
    Integer failNotifyThreshold
    Integer warnNotifyThreshold
    Integer monitorTimeoutSecs
    Boolean writeToMonitorLog
}
