/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.log

import io.xh.hoist.config.TypedConfigMap

/**
 * Typed representation of the `xhLogArchiveConfig` soft config, governing automatic
 * compression and archival of application log files.
 */
class LogArchiveConfig extends TypedConfigMap {

    /**
     * Age (days) beyond which log files are eligible for archival. Files newer than this
     * remain in place. A non-positive value disables archival.
     */
    Integer archiveAfterDays = 30

    /**
     * Name of the subdirectory (relative to the log root) where compressed log bundles are
     * written. A blank/null value disables archival regardless of `archiveAfterDays`.
     */
    String archiveFolder = 'archive'

    LogArchiveConfig(Map args) { init(args) }
}
