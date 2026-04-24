/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.telemetry.trace

import io.xh.hoist.config.TypedConfigMap

/**
 * Typed representation of `xhTraceConfig` values.
 */
class TraceConfig extends TypedConfigMap {
    boolean enabled
    double sampleRate
    boolean otlpEnabled
    Map otlpConfig

    /**
     * Master switch for the tail-sampling buffer. When false, server and client spans are
     * handed straight to the export pipeline as they complete — `sampleRate`, `sampleRules`,
     * `traceTimeoutMs`, and `maxBufferedTraces` are all unused and sampling is effectively
     * deferred to downstream (e.g. an OTel Collector). Defaults to true.
     */
    boolean tailSamplingEnabled = true

    /** Ordered match rules for per-trace sampling. Evaluated against the root span. First match wins. */
    List<Map> sampleRules = []

    /**
     * Max time (ms) a trace can sit in the tail-sampling buffer with no activity before it is
     * force-evicted. Not a trace-duration cap — long-running traces that stay active flush
     * normally when their root span ends.
     */
    long traceTimeoutMs = 600000

    /**
     * Cap on number of in-flight traces buffered by the tail sampler. New traces past the cap
     * are dropped with a WARN log until pressure eases.
     */
    int maxBufferedTraces = 10000

    /** Emit CLIENT spans for all JDBC operations. Defaults to false. */
    boolean jdbcTracingEnabled = false

    TraceConfig(args) { init(args) }
}
