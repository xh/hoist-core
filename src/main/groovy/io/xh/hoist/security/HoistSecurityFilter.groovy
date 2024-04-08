/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.security

import groovy.transform.CompileStatic
import io.xh.hoist.exception.ExceptionHandler
import io.xh.hoist.exception.InstanceNotAvailableException
import io.xh.hoist.log.LogSupport
import io.xh.hoist.util.Utils

import javax.servlet.*
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

@CompileStatic
class HoistSecurityFilter implements Filter, LogSupport {
    void init(FilterConfig filterConfig) {}
    void destroy() {}

    void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) {
        HttpServletRequest httpRequest = (HttpServletRequest) request
        HttpServletResponse httpResponse = (HttpServletResponse) response

        // Need to be *ready* before even attempting auth.
        if (!Utils.instanceReady) {
            ExceptionHandler exceptionHandler = Utils.exceptionHandler
            exceptionHandler.handleException(
                exception: new InstanceNotAvailableException('Application may be initializing. Please try again shortly.'),
                renderTo: httpResponse,
                logTo: this
            )
            return
        }

        BaseAuthenticationService svc = Utils.authenticationService
        if (svc.allowRequest(httpRequest, httpResponse)) {
            chain.doFilter(request, response)
        }
    }
}
