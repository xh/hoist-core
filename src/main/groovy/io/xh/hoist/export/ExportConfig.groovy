/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.export

import io.xh.hoist.config.TypedConfigMap

/**
 * Typed representation of the `xhExportConfig` soft config, governing Excel export behavior
 * for large tabular datasets.
 */
class ExportConfig extends TypedConfigMap {

    String getConfigName() { 'xhExportConfig' }

    /**
     * Cell count above which exports are written in streaming mode (SXSSF) to keep memory
     * pressure bounded. Read by server-side `GridExportImplService`.
     */
    Integer streamingCellThreshold = 100000

    /**
     * Cell count above which the client shows an in-progress toast during export generation.
     * Read by hoist-react `GridExportService` only — unused server-side.
     */
    Integer toastCellThreshold = 3000

    ExportConfig(Map args) { init(args) }
}
