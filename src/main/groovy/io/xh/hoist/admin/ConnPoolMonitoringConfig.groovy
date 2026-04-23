/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.admin

import io.xh.hoist.config.TypedConfigMap

/**
 * Typed representation of the `xhConnPoolMonitoringConfig` soft config, governing the
 * `ConnectionPoolMonitoringService` that periodically samples JDBC pool state.
 */
class ConnPoolMonitoringConfig extends TypedConfigMap {

    String getConfigName() { 'xhConnPoolMonitoringConfig' }

    /** Master switch for periodic JDBC pool sampling. */
    boolean enabled = true

    /** Interval (seconds) between pool snapshots. */
    Integer snapshotInterval = 60

    /** Max snapshots retained in memory before oldest entries are dropped. */
    Integer maxSnapshots = 1440

    /** When true, each snapshot is also emitted to the application log. */
    boolean writeToLog = false

    ConnPoolMonitoringConfig(Map args) { init(args) }
}
