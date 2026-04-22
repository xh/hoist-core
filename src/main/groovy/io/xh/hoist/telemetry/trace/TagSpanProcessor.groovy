/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.telemetry.trace

import groovy.transform.CompileStatic
import io.opentelemetry.context.Context
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.ReadWriteSpan
import io.opentelemetry.sdk.trace.ReadableSpan
import io.opentelemetry.sdk.trace.SpanProcessor
import io.xh.hoist.cluster.ClusterService
import io.xh.hoist.util.Utils

/**
 * {@link SpanProcessor} that stamps common Hoist attributes on every span at start time —
 * regardless of whether the span was created via {@link TraceService#createSpan} or by a
 * library (e.g. {@code opentelemetry-jdbc}).
 */
@CompileStatic
class TagSpanProcessor implements SpanProcessor {

    void onStart(Context ctx, ReadWriteSpan span) {
        def identityService = Utils.identityService,
            authUsername = identityService?.authUsername,
            username = identityService?.username
        if (authUsername) {
            span.setAttribute('user.name', authUsername)
            if (authUsername != username) {
                span.setAttribute('xh.impersonating', username)
            }
        }

        span.setAttribute('xh.isPrimary', Utils.clusterService.isPrimary)
        span.setAttribute('xh.instance', ClusterService.instanceName)
    }

    boolean isStartRequired() { true }
    boolean isEndRequired() { false }
    void onEnd(ReadableSpan span) {}
    CompletableResultCode shutdown() { CompletableResultCode.ofSuccess() }
    CompletableResultCode forceFlush() { CompletableResultCode.ofSuccess() }
}
