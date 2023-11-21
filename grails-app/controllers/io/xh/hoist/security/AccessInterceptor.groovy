/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.security

import groovy.transform.CompileStatic
import io.xh.hoist.exception.ExceptionHandler
import io.xh.hoist.exception.NotAuthorizedException
import io.xh.hoist.user.HoistUser
import io.xh.hoist.user.IdentityService
import java.lang.reflect.Method

@CompileStatic
class AccessInterceptor {

    IdentityService identityService
    ExceptionHandler xhExceptionHandler

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
        Class clazz = controllerClass?.clazz
        if (!clazz) {
            return true
        }


        String actionNm = actionName ?: controllerClass.defaultAction
        Method method = clazz.getMethod(actionNm)

        def access = method.getAnnotation(Access) ?:
                method.getAnnotation(AccessAll) ?:
                clazz.getAnnotation(Access) as Access ?:
                clazz.getAnnotation(AccessAll) as AccessAll

        if (access instanceof Access) {
            HoistUser user = identityService.getUser()
            return user.hasAllRoles(access.value()) ? true : handleUnauthorized()
        }

        if (access instanceof AccessAll) {
            return true
        }

        return handleUnauthorized()
    }

    //------------------------
    // Implementation
    //------------------------
    private boolean handleUnauthorized() {
        def username = identityService.username ?: 'UNKNOWN',
            ex = new NotAuthorizedException("""
                    You do not have the application role(s) required.
                    Currently logged in as: $username.
            """)
        xhExceptionHandler.handleException(
            exception: ex,
            logTo: identityService,
            logMessage: [_action: actionName],
            renderTo: response
        )
        return false
    }

    private boolean isWebSocketHandshake() {
        def upgradeHeader = request?.getHeader('upgrade')
        return upgradeHeader == 'websocket'
    }

}
