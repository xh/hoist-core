/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.admin

import io.xh.hoist.config.TypedConfigMap

/**
 * Typed representation of the `xhMemoryMonitoringConfig` soft config, governing the built-in
 * JVM memory-usage and GC sampling performed by `MemoryMonitoringService`.
 */
class MemoryMonitoringConfig extends TypedConfigMap {

    String getConfigName() { 'xhMemoryMonitoringConfig' }

    /** Master switch for periodic memory sampling. */
    boolean enabled = true

    /** Interval (seconds) between memory snapshots. */
    Integer snapshotInterval = 60

    /** Max snapshots retained in memory before oldest entries are dropped. */
    Integer maxSnapshots = 1440

    /**
     * Directory for on-demand heap dumps triggered via the Admin Console. Null disables
     * heap-dump support.
     */
    String heapDumpDir = null

    /**
     * When true, on graceful shutdown the most recent snapshots are persisted for
     * post-restart visibility into the prior instance. Disabled in local development.
     */
    boolean preservePastInstances = true

    /** Max number of past-instance snapshot sets retained on disk. */
    Integer maxPastInstances = 10

    /** When true, each snapshot is also emitted to the application log. */
    boolean writeToLog = true

    MemoryMonitoringConfig(Map args) { init(args) }
}
