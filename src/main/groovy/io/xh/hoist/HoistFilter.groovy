/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist

import groovy.transform.CompileStatic
import io.xh.hoist.exception.HttpException
import io.xh.hoist.log.LogSupport
import io.xh.hoist.telemetry.trace.SpanRef
import io.xh.hoist.util.Utils
import io.xh.hoist.websocket.HoistWebSocketConfigurer

import jakarta.servlet.*
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse

import static io.opentelemetry.api.trace.SpanKind.SERVER
import static io.xh.hoist.util.Utils.authenticationService
import static io.xh.hoist.util.Utils.identityService
import static io.xh.hoist.util.Utils.getIdentityService
import static io.xh.hoist.util.Utils.traceService
import static io.xh.hoist.util.Utils.traceContextService
import static io.xh.hoist.util.Utils.getClusterService

/**
 * Main Filter for all requests in Hoist.
 *
 * Wraps the entire request pipeline — restoring W3C trace context, enforcing security and
 * cluster readiness, dispatching to Grails, and catching uncaught exceptions. When tracing
 * is enabled, also creates the SERVER span for the request, captures any exception, and
 * stamps HTTP semantic-convention attributes.
 */
@CompileStatic
class HoistFilter implements Filter, LogSupport {

    /** Request attribute key for the per-request SERVER {@link SpanRef}, when tracing is enabled. */
    public static final String REQUEST_SPAN_ATTR = 'io.xh.hoist.requestSpan'

    void init(FilterConfig filterConfig) {}
    void destroy() {}

    void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) {
        HttpServletRequest httpRequest = (HttpServletRequest) request
        HttpServletResponse httpResponse = (HttpServletResponse) response

        try {
            rethrowErrorDispatches(httpRequest)

            // Always restore trace context, but conditionally add span here.
            try (def scope = traceContextService.restoreContextFromRequest(httpRequest)) {
                identityService.installIdentityFromRequest(httpRequest)
                shouldTrace(httpRequest) ?
                    handleRequestTraced(httpRequest, httpResponse, chain) :
                    handleRequest(httpRequest, httpResponse, chain)
            } finally {
                identityService.installThreadIdentity(null)
            }

        } catch (Throwable t) {
            Utils.handleException(exception: t, renderTo: httpResponse, logTo: this)
        }
    }

    //--------------------------
    // Implementation
    //--------------------------
    private handleRequest(HttpServletRequest req, HttpServletResponse res, FilterChain chain) {
        clusterService.ensureRunning()
        if (authenticationService.allowRequest(req, res)) {
            chain.doFilter(req, res)
        }
    }

    private boolean shouldTrace(HttpServletRequest req) {
        if (!traceService.enabled) return false
        def uri = req.requestURI
        if (!uri) return true
        if (uri == '/ping' || uri == '/xh/ping' || uri == '/xh/version') return false
        if (uri.endsWith(HoistWebSocketConfigurer.WEBSOCKET_PATH)) return false
        return true
    }

    private void handleRequestTraced(HttpServletRequest req, HttpServletResponse res, FilterChain chain) {
        // Span published on request: HoistInterceptor renames it post-routing
        // (e.g. "GET app/config"); BaseController records handled exceptions onto it.
        traceService.withSpan(
            name: req.method,
            kind: SERVER,
            tags: [
                'http.request.method': req.method,
                'url.path'           : req.requestURI,
                'url.scheme'         : req.scheme,
                'server.address'     : req.serverName,
                'server.port'        : req.serverPort as long,
                'client.address'     : req.remoteAddr,
                'user_agent.original': req.getHeader('User-Agent'),
                'xh.source'          : 'hoist'
            ],
            caller: this
        ) { SpanRef span ->
            req.setAttribute(REQUEST_SPAN_ATTR, span)
            try {
                handleRequest(req, res, chain)
            } catch (Throwable t) {
                Utils.handleException(exception: t, renderTo: res, logTo: this)
                span.recordException(t)
            } finally {
                span.setHttpStatusAndErrorStatus(res.status)
            }
        }
    }

    private static void rethrowErrorDispatches(HttpServletRequest req) {
        // Servlet error dispatches (e.g. multipart size exceeded) arrive with original exception
        if (req.dispatcherType == DispatcherType.ERROR) {
            def cause = (
                req.getAttribute('org.springframework.web.servlet.DispatcherServlet.EXCEPTION') ?:
                    req.getAttribute('jakarta.servlet.error.exception')
            ) as Throwable
            def message = cause?.message ?: 'An unexpected error occurred',
                statusCode = req.getAttribute('jakarta.servlet.error.status_code') as Integer ?: 500
            throw new HttpException(message, cause, statusCode)
        }
    }
}
