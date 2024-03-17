/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.security

import groovy.transform.CompileStatic
import io.xh.hoist.cluster.ClusterService
import io.xh.hoist.exception.ExceptionRenderer
import io.xh.hoist.exception.RoutineRuntimeException
import io.xh.hoist.log.LogSupport
import io.xh.hoist.util.Utils
import static io.xh.hoist.util.Utils.appContext

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
        ClusterService clusterService = (ClusterService) appContext.getBean('clusterService')
        if (!clusterService?.isReady) {
            ExceptionRenderer exceptionRenderer = Utils.exceptionRenderer
            exceptionRenderer.handleException(
                new RoutineRuntimeException('Application Initializing. Please try again shortly.'),
                httpRequest,
                httpResponse,
                this
            )
            return
        }

        BaseAuthenticationService svc = Utils.authenticationService
        if (svc.allowRequest(httpRequest, httpResponse)) {
            chain.doFilter(request, response)
        }
    }
}
