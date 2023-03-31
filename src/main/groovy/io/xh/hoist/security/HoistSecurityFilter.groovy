/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.security

import groovy.transform.CompileStatic
import io.xh.hoist.util.Utils
import javax.servlet.*
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

@CompileStatic
class HoistSecurityFilter implements Filter {
    void init(FilterConfig filterConfig) {}
    void destroy() {}

    void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) {
        HttpServletRequest httpRequest = (HttpServletRequest) request
        HttpServletResponse httpResponse = (HttpServletResponse) response
        BaseAuthenticationService svc = (BaseAuthenticationService) Utils.appContext.getBean('authenticationService')

        if (svc.allowRequest(httpRequest, httpResponse)) {
            chain.doFilter(request, response)
        }
    }

}
