/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.telemetry

import groovy.transform.CompileStatic
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.context.Context
import io.opentelemetry.sdk.trace.samplers.Sampler
import io.opentelemetry.sdk.trace.samplers.SamplingResult

/**
 * OTel {@link Sampler} that applies a per-span sample rate set via a thread-local.
 *
 * Callers set the rate with {@link #setSampleRate} before {@code startSpan()} and
 * clear it afterwards. Inherits a sampled parent's decision. Returns
 * {@code recordOnly()} (not {@code drop()}) for unsampled spans so that
 * {@link HoistBatchSpanProcessor} can still promote error spans.
 */
@CompileStatic
class HoistSampler implements Sampler {

    private final ThreadLocal<Double> _sampleRate = new ThreadLocal<>()

    /** Set the sample rate for the next span to be created on the current thread. */
    void setSampleRate(double rate) {
        _sampleRate.set(rate)
    }

    /** Clear the thread-local sample rate after span creation. */
    void clearSampleRate() {
        _sampleRate.remove()
    }

    @Override
    SamplingResult shouldSample(
        Context ctx,
        String traceId,
        String name,
        SpanKind kind,
        Attributes attrs,
        List parentLinks
    ) {
        def parent = Span.fromContext(ctx).spanContext
        if (parent.isValid()) {
            return parent.isSampled() ?
                SamplingResult.recordAndSample() :
                SamplingResult.recordOnly()
        }
        def rate = _sampleRate.get() ?: 0d
        return Math.random() < rate ?
            SamplingResult.recordAndSample() :
            SamplingResult.recordOnly()
    }

    @Override
    String getDescription() { return 'HoistSampler' }
}
