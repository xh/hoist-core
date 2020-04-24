/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2020 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.security

import groovy.transform.CompileStatic
import io.xh.hoist.exception.ExceptionRenderer
import io.xh.hoist.exception.NotAuthorizedException
import io.xh.hoist.user.HoistUser
import io.xh.hoist.user.IdentityService

@CompileStatic
class AccessInterceptor {

    IdentityService identityService
    ExceptionRenderer exceptionRenderer

    AccessInterceptor() {
        matchAll()
    }

    boolean before() {

        // Ignore Websockets -- these are destined for a non-controller based endpoint
        // established via a spring-websocket configuration mapping. (Note this is *not* currently
        // built into Hoist but is checked / allowed for here.)
        if (isWebSocketHandshake()) {
            return true
        }

        // Ignore improperly mapped requests -- these will be handled via url 404 mapping
        if (!controllerClass) {
            return true
        }
        
        Class clazz = controllerClass.clazz
        AccessAll accessAll = clazz.getAnnotation(AccessAll)
        if (accessAll) {
            return true
        }

        Access access = clazz.getAnnotation(Access)
        if (access) {
            HoistUser user = identityService.getUser()
            if (user.hasAllRoles(access.value())) {
                return true
            }
        }

        def username = identityService.getUser()?.username ?: 'UNKNOWN',
            ex = new NotAuthorizedException("""
                    You do not have the application role(s) required to access this content. 
                    Currently logged in as: $username.
            """)
        exceptionRenderer.handleException(ex, request, response, identityService)
        return false
    }


    //------------------------
    // Implementation
    //------------------------
    private boolean isWebSocketHandshake() {
        def upgradeHeader = request?.getHeader('upgrade')
        return upgradeHeader == 'websocket'
    }

}
