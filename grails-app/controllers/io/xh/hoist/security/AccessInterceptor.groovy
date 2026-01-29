/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.security

import groovy.transform.CompileStatic
import io.xh.hoist.exception.NotAuthorizedException
import io.xh.hoist.exception.NotFoundException
import io.xh.hoist.log.LogSupport
import io.xh.hoist.user.HoistUser
import io.xh.hoist.user.IdentityService
import io.xh.hoist.util.Utils
import io.xh.hoist.websocket.HoistWebSocketConfigurer
import jakarta.servlet.http.HttpServletRequest

import java.lang.annotation.Annotation
import java.lang.reflect.Method

import static org.springframework.util.ReflectionUtils.findMethod

@CompileStatic
class AccessInterceptor implements LogSupport {

    IdentityService identityService

    static List<Class<? extends Annotation>> annotations = [
        Access,
        AccessRequiresRole,
        AccessRequiresAllRoles,
        AccessRequiresAnyRole,
        AccessAll
    ]

    AccessInterceptor() {
        matchAll()
    }

    boolean before() {
        try {
            def req = getRequest()
            if (isWebSocketHandshake(req) || isActuator(req) ) {
                return true
            }

            // Get controller method, or throw 404 (Not Found).
            HoistUser user = identityService.user
            Class clazz = controllerClass?.clazz
            String actionNm = actionName ?: controllerClass?.defaultAction
            Method method = clazz && actionNm ? findMethod(clazz, actionNm) : null
            if (!method) throw new NotFoundException()

            // Eval security annotations, return true if allowed, or throw 403 (Forbidden).
            def ann = annotations.findResult {method.getAnnotation(it)} ?: annotations.findResult {clazz.getAnnotation(it)}
            if (
                (ann instanceof AccessAll) ||
                (ann instanceof AccessRequiresRole && user?.hasRole(ann.value() as String)) ||
                (ann instanceof AccessRequiresAnyRole  && user?.hasAnyRole(ann.value() as String[])) ||
                (ann instanceof AccessRequiresAllRoles && user?.hasAllRoles(ann.value() as String[])) ||
                (ann instanceof Access && user?.hasAllRoles(ann.value() as String[]))
            ) return true

            def username = user?.username ?: 'UNKNOWN'
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
    private boolean isWebSocketHandshake(HttpServletRequest req) {
        def upgradeHeader = req?.getHeader('upgrade'),
            uri = req?.requestURI
        upgradeHeader == 'websocket' && uri?.endsWith(HoistWebSocketConfigurer.WEBSOCKET_PATH)
    }

    private boolean isActuator(HttpServletRequest req) {
        req?.requestURI.startsWith('/actuator/')
    }
}
