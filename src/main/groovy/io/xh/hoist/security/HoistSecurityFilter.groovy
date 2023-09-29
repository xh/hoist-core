/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.security

import groovy.transform.CompileStatic
import io.xh.hoist.cluster.ClusterService
import javax.servlet.*
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

import static io.xh.hoist.util.Utils.appContext

@CompileStatic
class HoistSecurityFilter implements Filter {
    void init(FilterConfig filterConfig) {}
    void destroy() {}

    void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) {
        HttpServletRequest httpRequest = (HttpServletRequest) request
        HttpServletResponse httpResponse = (HttpServletResponse) response

        // Need to be *ready* before even attempting auth.
        ClusterService clusterService = (ClusterService) appContext.getBean('clusterService')
        if (!clusterService?.isReady) {
            httpResponse.setStatus(503)
            httpResponse.flushBuffer()
            return
        }

        BaseAuthenticationService authSvc = (BaseAuthenticationService) appContext.getBean('authenticationService')
        if (authSvc.allowRequest(httpRequest, httpResponse)) {
            chain.doFilter(request, response)
        }
    }

}
