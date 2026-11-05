/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.exception

import groovy.transform.CompileStatic
import io.xh.hoist.log.LogSupport
import io.xh.hoist.telemetry.trace.SpanRef
import io.xh.hoist.util.Utils
import jakarta.servlet.ServletException
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.codehaus.groovy.runtime.InvokerInvocationException
import org.grails.web.servlet.mvc.GrailsWebRequest
import org.grails.web.servlet.mvc.exceptions.GrailsMVCException
import org.springframework.core.Ordered
import org.springframework.web.servlet.HandlerExceptionResolver
import org.springframework.web.servlet.ModelAndView

import java.lang.reflect.InvocationTargetException

import static io.xh.hoist.HoistFilter.REQUEST_SPAN_ATTR

/**
 * Renders exceptions that escape Grails controller actions, in place of the default Grails
 * `exceptionHandler` bean.
 *
 * Most exceptions never get here - `BaseController.handleException()` handles them within the
 * action. That handler only sees `Exception`s, so a JVM `Error` passes it by and reaches Spring's
 * resolver chain instead. Examples are a failed Groovy `assert`, a `StackOverflowError`, or a
 * `LinkageError`.
 * The Grails default would then try to render an `/error` view, which Hoist does not provide,
 * and the client would get a "Could not resolve view" message rather than the real error.
 *
 * This resolver instead unwraps the original exception and hands it to
 * {@link Utils#handleException}, which logs and renders it as JSON like any other Hoist error.
 * The hoist-core plugin installs it as `exceptionHandler`, and apps may replace it with their
 * own bean of that name.
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
        def t = unwrap(ex),
            webRequest = GrailsWebRequest.lookup(request)

        (request.getAttribute(REQUEST_SPAN_ATTR) as SpanRef)?.recordException(t)
        Utils.handleException(
            exception: t,
            logTo: this,
            logMessage: [controller: webRequest?.controllerName, action: webRequest?.actionName],
            renderTo: response
        )

        // An empty ModelAndView tells Spring the exception is handled and nothing more to render.
        return new ModelAndView()
    }

    /**
     * Recover the exception actually thrown. Grails invokes actions reflectively, so an action's
     * `Error` arrives in an `InvocationTargetException`. Spring wraps an `Error` thrown outside the
     * action, such as by an interceptor, in a `ServletException`. The Groovy and Grails wrappers are
     * those the default Grails resolver also unwraps.
     */
    protected Throwable unwrap(Throwable t) {
        while (t.cause && isWrapper(t)) t = t.cause
        return t
    }

    private static boolean isWrapper(Throwable t) {
        t instanceof InvocationTargetException ||
            t instanceof InvokerInvocationException ||
            t instanceof GrailsMVCException ||
            (t instanceof ServletException && t.cause instanceof Error)
    }
}
