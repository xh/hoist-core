/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.telemetry

import groovy.transform.CompileStatic
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.context.Scope

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
    final Span span
    final Scope scope

    SpanRef(Span span, Scope scope) {
        this.span = span
        this.scope = scope
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

    /** Set the HTTP response status code and mark the span as ERROR for 5xx. */
    void setHttpStatus(int statusCode) {
        span.setAttribute('http.response.status_code', (long) statusCode)
        if (statusCode >= 500) span.setStatus(StatusCode.ERROR)
    }

    /** Record an exception on the span and set its status to ERROR. */
    void recordException(Throwable t) {
        span.setStatus(StatusCode.ERROR, t.message ?: t.class.name)
        span.recordException(t)
    }

    /** Close the scope and end the span. */
    void close() {
        scope.close()
        span.end()
    }
}
