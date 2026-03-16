/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.telemetry

import groovy.transform.MapConstructor

/**
 * Typed representation of `xhMetricsConfig` values.
 */
@MapConstructor
class MetricsConfig {
    boolean prometheusEnabled
    Map prometheusConfig
    boolean otlpEnabled
    Map otlpConfig
}
