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
import io.xh.hoist.exception.NotFoundException
import io.xh.hoist.log.LogSupport
import io.xh.hoist.user.IdentityService
import java.lang.reflect.Method

import static org.springframework.util.ReflectionUtils.findMethod

@CompileStatic
class AccessInterceptor implements LogSupport {

    IdentityService identityService
    ExceptionHandler xhExceptionHandler

    AccessInterceptor() {
        matchAll()
    }

    boolean before() {
        try {

            // Ignore Websockets -- these are destined for a non-controller based endpoint
            // established via a spring-websocket configuration mapping. (Note this is *not* currently
            // built into Hoist but is checked / allowed for here.)
            if (isWebSocketHandshake()) {
                return true
            }

            // Get controller method, or 404
            Class clazz = controllerClass?.clazz
            String actionNm = actionName ?: controllerClass?.defaultAction
            Method method = clazz && actionNm ? findMethod(clazz, actionNm) : null
            if (!method) throw new NotFoundException()

            // Eval method annotations, and return true or 401
            def access = method.getAnnotation(Access) ?:
                method.getAnnotation(AccessAll) ?:
                    clazz.getAnnotation(Access) as Access ?:
                        clazz.getAnnotation(AccessAll) as AccessAll

            if (access instanceof AccessAll ||
                (access instanceof Access && identityService.user.hasAllRoles(access.value()))
            ) {
                return true
            }

            def username = identityService.username ?: 'UNKNOWN'
            throw new NotAuthorizedException(
                "You do not have the required role(s) for this action. Currently logged in as: $username."
            )
        } catch (Exception e) {
            xhExceptionHandler.handleException(
                exception: e,
                logMessage: [controller: controllerClass?.name, action: actionName],
                logTo: this,
                renderTo: response
            )
        }
    }

    //------------------------
    // Implementation
    //------------------------
    private boolean isWebSocketHandshake() {
        def upgradeHeader = request?.getHeader('upgrade')
        return upgradeHeader == 'websocket'
    }

}
