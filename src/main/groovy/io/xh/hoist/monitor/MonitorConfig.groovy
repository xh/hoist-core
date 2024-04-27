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
