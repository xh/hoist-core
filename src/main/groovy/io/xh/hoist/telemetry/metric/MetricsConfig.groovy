/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.telemetry.metric

import io.xh.hoist.config.TypedConfigMap

/**
 * Typed representation of `xhMetricsConfig` values.
 */
class MetricsConfig extends TypedConfigMap {
    boolean prometheusEnabled
    Map prometheusConfig
    boolean otlpEnabled
    Map otlpConfig

    MetricsConfig(Map args) { init(args) }
}
