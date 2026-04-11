/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.telemetry

import groovy.transform.InheritConstructors
import io.xh.hoist.config.TypedConfigMap

/**
 * Typed representation of `xhTraceConfig` values.
 */
@InheritConstructors
class TraceConfig extends TypedConfigMap {
    boolean enabled
    double sampleRate
    boolean otlpEnabled
    Map otlpConfig

    /** Ordered tag-match rules for sampling. First match wins. */
    List<Map> samplingRules = []

    /** Always export error spans, bypassing sample-rate filtering. Defaults to true. */
    boolean alwaysSampleErrors = true
}
