/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.telemetry

import groovy.transform.CompileStatic
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.TraceFlags
import io.opentelemetry.context.Context
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.ReadableSpan
import io.opentelemetry.sdk.trace.ReadWriteSpan
import io.opentelemetry.sdk.trace.SpanProcessor
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter

/**
 * SpanProcessor that delegates to a {@link BatchSpanProcessor} and optionally promotes
 * unsampled error spans so they get batched and exported.
 *
 * {@code BatchSpanProcessor.onEnd()} rejects spans where {@code isSampled()} is false.
 * When {@code alwaysSampleErrors} is enabled in the trace config, this processor wraps
 * recordOnly spans that ended in error with a {@link PromotedErrorSpan} that flips the
 * sampled flag, allowing them through the gate and into the batch queue.
 */
@CompileStatic
class HoistBatchSpanProcessor implements SpanProcessor {

    private final BatchSpanProcessor batch
    private boolean alwaysSampleErrors

    HoistBatchSpanProcessor(SpanExporter exporter, boolean alwaysSampleErrors) {
        this.batch = BatchSpanProcessor.builder(exporter).build()
        this.alwaysSampleErrors = alwaysSampleErrors
    }

    void onStart(Context ctx, ReadWriteSpan span) { batch.onStart(ctx, span) }

    boolean isStartRequired() { batch.isStartRequired() }

    boolean isEndRequired() { true }

    void onEnd(ReadableSpan span) {
        if (alwaysSampleErrors &&
            !span.spanContext.isSampled() &&
            span.toSpanData().status.statusCode == StatusCode.ERROR
        ) {
            span = new PromotedErrorSpan(span)
        }
        batch.onEnd(span)
    }

    CompletableResultCode shutdown() { batch.shutdown() }

    CompletableResultCode forceFlush() { batch.forceFlush() }

    /**
     * Wraps a ReadableSpan, overriding getSpanContext() to return a sampled version.
     * This lets BatchSpanProcessor accept spans that were originally recordOnly.
     */
    @CompileStatic
    private static class PromotedErrorSpan implements ReadableSpan {
        @Delegate(excludes = ['getSpanContext'])
        private final ReadableSpan delegate

        private final SpanContext promotedContext

        PromotedErrorSpan(ReadableSpan span) {
            this.delegate = span
            def ctx = span.spanContext
            this.promotedContext = SpanContext.create(
                ctx.traceId, ctx.spanId,
                TraceFlags.sampled, ctx.traceState
            )
        }

        @Override
        SpanContext getSpanContext() { promotedContext }
    }
}
