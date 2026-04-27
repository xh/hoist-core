/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.telemetry.metric

import io.xh.hoist.config.TypedConfigMap

/**
 * Typed representation of the `xhMetricsConfig` soft config, controlling which publication
 * registries are active for observable metrics. Defaults declared here are applied when the
 * backing AppConfig is missing individual keys.
 */
class MetricsConfig extends TypedConfigMap {

    /** When true, a Prometheus meter registry is installed and exposed via the scrape endpoint. */
    boolean prometheusEnabled = false

    /** Configuration keys forwarded to Micrometer's `PrometheusConfig` (prefixed with `prometheus.`). */
    Map prometheusConfig = [:]

    /** When true, meters are exported via Micrometer's OTLP registry. */
    boolean otlpEnabled = false

    /**
     * Connection and naming options for the OTLP registry (endpoint, resource attributes, etc.).
     * See `MetricsService` for the supported keys.
     */
    Map otlpConfig = [:]

    MetricsConfig(Map args) { init(args) }
}
