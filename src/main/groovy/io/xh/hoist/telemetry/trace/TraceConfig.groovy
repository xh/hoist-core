/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.telemetry.trace

import io.xh.hoist.config.TypedConfigMap

/**
 * Typed representation of the `xhTraceConfig` soft config, controlling distributed tracing
 * behavior, sampling, and OTLP export. Defaults declared here are applied when the backing
 * AppConfig is missing individual keys.
 */
class TraceConfig extends TypedConfigMap {

    String getConfigName() { 'xhTraceConfig' }

    /** Master switch for distributed tracing. When false, no spans are recorded or exported. */
    boolean enabled = false

    /** Fraction of root spans to record (0.0–1.0). Overridden per-span by any matching `sampleRules`. */
    double sampleRate = 1.0

    /**
     * Ordered tag-match rules used to adjust per-span sample rates before the global `sampleRate`
     * is applied. First match wins; see the tracing documentation for rule shape.
     */
    List<Map> sampleRules = []

    /** When true, recorded spans are exported via the built-in OTLP HTTP exporter. */
    boolean otlpEnabled = false

    /**
     * Connection options for the built-in OTLP HTTP span exporter. Recognized keys:
     *  - `endpoint` (String) — collector URL
     *  - `timeout` (String/long, ms) — parsed by `TraceService` at wire time
     *  - `headers` (Map<String, String>) — extra HTTP headers per export
     */
    Map otlpConfig = [:]

    /** Emit CLIENT spans for all JDBC operations. */
    boolean jdbcTracingEnabled = false

    TraceConfig(Map args) { init(args) }
}
