/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.monitor

import io.xh.hoist.config.TypedConfigMap

/**
 * Typed representation of the `xhMonitorConfig` soft config, providing parameters that
 * govern status-monitor polling frequency, alerting thresholds, and log output. Defaults
 * declared here are applied when the backing AppConfig is missing individual keys.
 */
class MonitorConfig extends TypedConfigMap {

    /** Interval at which the primary instance re-runs all active monitors. */
    Integer monitorRefreshMins = 10

    /** Delay after cluster startup before the first monitor run is triggered. */
    Integer monitorStartupDelayMins = 1

    /** Interval at which repeat-alert notifications are re-sent while the cluster remains in an alerting state. */
    Integer monitorRepeatNotifyMins = 60

    /** Number of consecutive FAIL cycles required before a monitor triggers an alert. */
    Integer failNotifyThreshold = 2

    /** Number of consecutive WARN cycles required before a monitor triggers an alert. */
    Integer warnNotifyThreshold = 5

    /** Per-monitor timeout (seconds) — evaluation runs that exceed this duration are failed. */
    Integer monitorTimeoutSecs = 15

    /** When true, monitor results are emitted to the application log after each evaluation cycle. */
    Boolean writeToMonitorLog = true

    MonitorConfig(Map args) { init(args) }
}
