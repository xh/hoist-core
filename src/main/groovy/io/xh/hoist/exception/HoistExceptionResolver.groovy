/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.exception

import groovy.transform.CompileStatic
import io.xh.hoist.log.LogSupport
import io.xh.hoist.log.SimpleLogger
import io.xh.hoist.util.Utils
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.grails.web.servlet.mvc.GrailsWebRequest
import org.springframework.core.Ordered
import org.springframework.web.servlet.HandlerExceptionResolver
import org.springframework.web.servlet.ModelAndView

/**
 * Logs and renders every exception thrown during Spring MVC dispatch, in place of the default
 * Grails `exceptionHandler` bean.
 *
 * This covers `HoistInterceptor` access checks and controller actions alike, for `Exception`s
 * and JVM `Error`s such as a failed Groovy `assert`, a `StackOverflowError`, or a `LinkageError`.
 * It hands the exception to {@link Utils#handleException} for the standard unwrapping, logging,
 * and JSON rendering, logging under the mapped controller's class.
 *
 * The Grails default would instead try to render an `/error` view, which Hoist does not provide,
 * and the client would get a "Could not resolve view" message rather than the real error.
 * The hoist-core plugin installs this resolver as `exceptionHandler`, and apps may replace it
 * with their own bean of that name.
 *
 * Exceptions thrown before dispatch, from `HoistFilter` or authentication, never reach a resolver
 * and are handled by the filter itself.
 */
@CompileStatic
class HoistExceptionResolver implements HandlerExceptionResolver, Ordered, LogSupport {

    /** Last, matching the Grails default, so Spring's built-in resolvers still run first. */
    int getOrder() {
        LOWEST_PRECEDENCE
    }

    ModelAndView resolveException(
        HttpServletRequest request,
        HttpServletResponse response,
        Object handler,
        Exception ex
    ) {
        // Log under the mapped controller, if any, so its log level and stacktrace settings apply.
        def controllerClass = GrailsWebRequest.lookup(request)?.controllerClass?.clazz

        Utils.handleException(
            exception: ex,
            logTo: controllerClass ? new SimpleLogger(controllerClass.name) : this,
            renderTo: response,
            request: request
        )

        // An empty ModelAndView tells Spring the exception is handled and nothing more to render.
        return new ModelAndView()
    }
}
