/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist

import groovy.transform.CompileStatic
import io.opentelemetry.api.trace.Span
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.TextMapGetter
import io.xh.hoist.log.LogSupport
import io.xh.hoist.util.Utils

import jakarta.servlet.*
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse

import static io.xh.hoist.util.Utils.authenticationService
import static io.xh.hoist.util.Utils.identityService
import static io.xh.hoist.util.Utils.getClusterService
import static io.xh.hoist.util.Utils.getTraceService

/**
 * Main Filter for all requests in Hoist.
 *
 * This filter is installed by Hoist Core with very high preference and is designed to
 * precede/wrap the built-in grails filters.
 *
 * Implements security, app ready checking, and catches uncaught exceptions.
 */
@CompileStatic
class HoistFilter implements Filter, LogSupport {
    void init(FilterConfig filterConfig) {}
    void destroy() {}

    void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) {
        HttpServletRequest httpRequest = (HttpServletRequest) request
        HttpServletResponse httpResponse = (HttpServletResponse) response

        try {
            clusterService.ensureRunning()
            if (authenticationService.allowRequest(httpRequest, httpResponse)) {
                doFilterTraced(httpRequest, httpResponse, chain)
            }
        } catch (Throwable t) {
            Utils.handleException(
                exception: t,
                renderTo: httpResponse,
                logTo: this
            )
        }
    }

    private void doFilterTraced(
        HttpServletRequest httpRequest,
        HttpServletResponse httpResponse,
        FilterChain chain
    ) {
        def tracingSvc = traceService
        if (!tracingSvc.enabled) {
            chain.doFilter(httpRequest, httpResponse)
            return
        }

        // Extract incoming W3C trace context from request headers
        def propagator = tracingSvc.otelSdk.propagators.textMapPropagator,
            parentContext = propagator.extract(Context.current(), httpRequest, HTTP_GETTER),
            spanName = "${httpRequest.method} ${httpRequest.requestURI}".toString(),
            tags = [
                'http.method': httpRequest.method,
                'http.url'   : httpRequest.requestURL.toString(),
                'source'     : 'hoist'
            ] as Map<String, String>

        tracingSvc.withServerSpan(spanName, parentContext, tags) {
            chain.doFilter(httpRequest, httpResponse)

            // Set response attributes on current span
            def span = Span.current()
            span.setAttribute('http.status_code', (long) httpResponse.status)
            def username = identityService.username
            if (username) span.setAttribute('username', username)
        }
    }

    private final TextMapGetter<HttpServletRequest> HTTP_GETTER = new TextMapGetter<HttpServletRequest>() {
        Iterable<String> keys(HttpServletRequest carrier) {
            Collections.list(carrier.headerNames)
        }
        String get(HttpServletRequest carrier, String key) {
            carrier.getHeader(key)
        }
    }
}
