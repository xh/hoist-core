/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2025 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.security

import groovy.transform.CompileStatic
import io.xh.hoist.exception.NotAuthorizedException
import io.xh.hoist.exception.NotFoundException
import io.xh.hoist.log.LogSupport
import io.xh.hoist.user.IdentityService
import io.xh.hoist.util.Utils
import io.xh.hoist.websocket.HoistWebSocketConfigurer

import java.lang.annotation.Annotation
import java.lang.reflect.Method

import static org.springframework.util.ReflectionUtils.findMethod

@CompileStatic
class AccessInterceptor implements LogSupport {

    IdentityService identityService

    static List<Class<? extends Annotation>> annotations = [Access, RequiresAll, RequiresAny, AccessAll]

    AccessInterceptor() {
        matchAll()
    }

    boolean before() {
        try {
            // Ignore websockets - these are destined for a non-controller based endpoint
            // established via a spring-websocket configuration mapping.
            // Note that websockets are not always enabled by Hoist apps but must be supported here.
            if (isWebSocketHandshake()) {
                return true
            }

            // Get controller method, or throw 404 (Not Found).
            Class clazz = controllerClass?.clazz
            String actionNm = actionName ?: controllerClass?.defaultAction
            Method method = clazz && actionNm ? findMethod(clazz, actionNm) : null
            if (!method) throw new NotFoundException()

            // Eval @security annotations, return true if allowed, or throw 403 (Forbidden).
            def ann = annotations.find {method.getAnnotation(it)} ?: annotations.find {clazz.getAnnotation(it)}
            switch (ann) {
                case AccessAll: return true
                case Access:
                    return identityService.user.hasAllRoles(ann.value())
                case RequiresAll:
                    return identityService.user.hasAllRoles(ann.value())
                case RequiresAny:
                    return identityService.user.hasAnyRole(ann.value())
            }

            def username = identityService.username ?: 'UNKNOWN'
            throw new NotAuthorizedException(
                "You do not have the required role(s) for this action. Currently logged in as: $username."
            )
        } catch (Exception e) {
            Utils.handleException(
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
        def req = getRequest(),
            upgradeHeader = req?.getHeader('upgrade'),
            uri = req?.requestURI

        return upgradeHeader == 'websocket' && uri?.endsWith(HoistWebSocketConfigurer.WEBSOCKET_PATH)
    }

    private getMethodAnnotation(Method md) {

    }

}
