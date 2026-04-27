/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.telemetry.trace

import groovy.transform.CompileStatic
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.context.Scope
import io.xh.hoist.exception.RoutineException

import static io.xh.hoist.util.Utils.exceptionHandler

/**
 * An active span and its associated scope, returned by {@link TraceService#createSpan}.
 *
 * The caller is responsible for closing this when done — typically in a finally block:
 * <pre>
 * def spanRef = traceService.createSpan(name: 'myOp', kind: SpanKind.SERVER)
 * try {
 *     // ... do work, span is current context ...
 * } finally {
 *     spanRef?.close()
 * }
 * </pre>
 */
@CompileStatic
class SpanRef implements Closeable {

    /**
     * No-op SpanRef used when tracing is disabled — all methods are safe to call and
     * do nothing. Returned by {@link TraceService#withSpan} so closures can always
     * rely on a non-null span.
     */
    static final SpanRef NOOP = new SpanRef(Span.invalid, Scope.noop(), SpanKind.INTERNAL)

    final Span span
    final Scope scope
    final SpanKind kind

    SpanRef(Span span, Scope scope, SpanKind kind = SpanKind.INTERNAL) {
        this.span = span
        this.scope = scope
        this.kind = kind
    }

    /** Update the span's display name. */
    void updateName(String name) {
        span.updateName(name)
    }

    /** Set attributes on the span. Values can be String, long, boolean, or double. */
    void setTags(Map<String, Object> tags) {
        tags.each { k, v -> setTag(k, v) }
    }

    /** Set a single attribute on the span. Supports String, long, boolean, double. */
    void setTag(String key, Object value) {
        switch (value) {
            case Long:    span.setAttribute(key, (long) value); break
            case Integer: span.setAttribute(key, (long) value); break
            case Boolean: span.setAttribute(key, (boolean) value); break
            case Double:  span.setAttribute(key, (double) value); break
            default:      span.setAttribute(key, value?.toString())
        }
    }

    /**
     * Set the HTTP response status code and mark the span as ERROR when appropriate.
     * SERVER spans use >= 500 (server fault), CLIENT spans use >= 400 (request failed).
     */
    void setHttpStatusAndErrorStatus(int statusCode) {
        setTag('http.response.status_code', statusCode)
        def errorThreshold = kind == SpanKind.CLIENT ? 400 : 500
        if (statusCode >= errorThreshold) span.setStatus(StatusCode.ERROR)
    }

    /**
     * Record an exception as an event on the span and mark the span status as ERROR
     * with a summary description derived from the throwable. No-op for {@link RoutineException}.
     */
    void recordExceptionAndErrorStatus(Throwable t) {
        // Skip routine exceptions -- Datadog's OTLP intake maps any exception event onto error.* tags.
        if (t instanceof RoutineException) return
        span.recordException(t)
        span.setStatus(StatusCode.ERROR, exceptionHandler.summaryTextForThrowable(t))
    }

    /** Record an exception as an event on the span. Does not change span status.*/
    void recordException(Throwable t) {
        // Skip routine exceptions -- Datadog's OTLP intake maps any exception event onto error.* tags.
        if (t instanceof RoutineException) return
        span.recordException(t)
    }

    /** Close the scope and end the span. */
    void close() {
        scope.close()
        span.end()
    }
}
