/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */

package io.xh.hoist

import groovy.transform.CompileStatic
import io.xh.hoist.exception.InstanceNotAvailableException
import io.xh.hoist.log.LogSupport
import io.xh.hoist.security.BaseAuthenticationService
import io.xh.hoist.util.Utils

import javax.servlet.*
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

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

        // Need to be *ready* before even attempting auth.
        if (!Utils.instanceReady) {
            Utils.exceptionHandler.handleException(
                exception: new InstanceNotAvailableException('Application may be initializing. Please try again shortly.'),
                renderTo: httpResponse,
                logTo: this
            )
            return
        }

        BaseAuthenticationService authSvc = Utils.authenticationService
        if (authSvc.allowRequest(httpRequest, httpResponse)) {
            try {
                chain.doFilter(request, response)
            } catch (Throwable t) {
                Utils.exceptionHandler.handleException(
                    exception: t,
                    renderTo: httpResponse,
                    logTo: this
                )
            }
        }
    }
}
