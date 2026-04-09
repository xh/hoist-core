/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.telemetry

import groovy.transform.CompileStatic
import io.opentelemetry.context.Scope
import io.xh.hoist.log.LogSupport

import jakarta.servlet.http.HttpServletRequest

import static io.opentelemetry.api.trace.SpanKind.SERVER

/**
 * Grails interceptor that creates a SERVER span for each controller action.
 *
 * Extracts incoming W3C trace context from request headers and sets standard
 * HTTP semantic convention attributes on the span. Runs at high priority to
 * wrap all other interceptors (including AccessInterceptor).
 */
@CompileStatic
class TraceInterceptor implements LogSupport {

    TraceService traceService

    /** Run after AccessInterceptor (default order 0) — only trace authorized requests. */
    int order = 10

    TraceInterceptor() {
        matchAll()
    }

    boolean before() {
        if (!traceService.enabled || actionName == 'submitSpans') return true

        def req = request as HttpServletRequest,
            route = "${controllerName}/${actionName ?: 'index'}",
            spanRef = traceService.createSpan(
                name: "${req.method} $route",
                kind: SERVER,
                tags: [
                    'http.request.method' : req.method,
                    'http.route'          : route,
                    'url.path'            : req.requestURI,
                    'url.scheme'          : req.scheme,
                    'server.address'      : req.serverName,
                    'server.port'         : req.serverPort,
                    'client.address'      : req.remoteAddr,
                    'user_agent.original' : req.getHeader('User-Agent'),
                    'hoist.source'        : 'hoist'
                ],
                caller: this
            )

        if (spanRef) request.setAttribute('xh.spanRef', spanRef)
        return true
    }

    /**
     * Complete the span with response attributes. Called in afterView() to ensure
     * the span is closed even if the action throws (afterView runs in a finally block).
     */
    void afterView() {
        try (def spanRef = request.getAttribute('xh.spanRef') as SpanRef) {
            spanRef?.setHttpStatus(response.status)
        }
    }
}
