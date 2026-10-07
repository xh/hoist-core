/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.test

import grails.web.Action
import groovy.transform.CompileStatic
import io.xh.hoist.HoistInterceptor
import io.xh.hoist.exception.ExceptionHandler
import io.xh.hoist.util.Utils

import java.lang.reflect.Method

/**
 * Assertion helpers for common Hoist testing concerns - exception-to-status mapping and
 * controller action security.
 */
@CompileStatic
final class HoistAssertions {

    private HoistAssertions() {}

    /**
     * The HTTP status code Hoist would render for this exception - e.g. 403 for a
     * `NotAuthorizedException`, 400 for any `RoutineException`, and 500 for anything unexpected.
     *
     * Uses the application's `ExceptionHandler` if one is available, otherwise the default.
     */
    static int httpStatusFor(Throwable t) {
        exceptionHandler.getHttpStatus(t)
    }

    /**
     * True if Hoist treats this exception as a routine, expected part of application flow (logged
     * at DEBUG) rather than an unexpected error.
     */
    static boolean isRoutine(Throwable t) {
        exceptionHandler.isRoutine(t)
    }

    /**
     * Names of the actions of a controller class that Hoist would reject for every user, as they
     * carry no access annotation - neither on the action method nor on the controller class.
     *
     * Hoist's `HoistInterceptor` denies such requests at runtime with a 403, but nothing flags them
     * at startup, so use this (or {@link #assertAllActionsSecured}) in a spec to catch them early.
     * Actions are the public methods annotated with `@grails.web.Action`, as marked by the Grails
     * compiler - i.e. the class must have been compiled as a Grails controller artifact.
     *
     * @return sorted action names, empty if all actions are secured
     */
    static List<String> findUnsecuredActions(Class controllerClass) {
        controllerClass.methods
            .findAll { Method m -> m.isAnnotationPresent(Action) && !findAccessAnnotation(controllerClass, m) }
            .collect { Method m -> m.name }
            .unique()
            .sort()
    }

    /**
     * Fail with an AssertionError naming the unsecured actions, if the given controller class has
     * any - see {@link #findUnsecuredActions}.
     */
    static void assertAllActionsSecured(Class controllerClass) {
        def unsecured = findUnsecuredActions(controllerClass)
        if (unsecured) {
            throw new AssertionError(
                "${controllerClass.name} has actions with no access annotation: ${unsecured.join(', ')}".toString()
            )
        }
    }

    //------------------------
    // Implementation
    //------------------------
    // Mirrors the lookup in HoistInterceptor.before() - method annotation first, then class.
    private static Object findAccessAnnotation(Class clazz, Method method) {
        HoistInterceptor.annotations.findResult { method.getAnnotation(it) } ?:
            HoistInterceptor.annotations.findResult { clazz.getAnnotation(it) }
    }

    private static ExceptionHandler getExceptionHandler() {
        try {
            Utils.exceptionHandler ?: new ExceptionHandler()
        } catch (IllegalStateException ignored) {
            new ExceptionHandler()   // no application context available
        }
    }
}
