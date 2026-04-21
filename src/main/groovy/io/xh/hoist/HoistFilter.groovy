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
import io.xh.hoist.util.Utils

import jakarta.servlet.*
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse

import static io.xh.hoist.util.Utils.authenticationService
import static io.xh.hoist.util.Utils.traceSupportService
import static io.xh.hoist.util.Utils.getClusterService

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

        try (def scope = traceSupportService.restoreContextFromRequest(httpRequest)) {
            clusterService.ensureRunning()
            if (authenticationService.allowRequest(httpRequest, httpResponse)) {

                // Rethrow spring/tc errors early. Intentionally post-auth and context restore
                rethrowErrorDispatches(httpRequest)

                chain.doFilter(request, response)
            }
        } catch (Throwable t) {
            Utils.handleException(
                exception: t,
                renderTo: httpResponse,
                logTo: this
            )
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
