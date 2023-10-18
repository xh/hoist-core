/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.exception

import grails.util.GrailsUtil
import groovy.transform.CompileStatic
import io.xh.hoist.json.JSONFormat
import io.xh.hoist.json.JSONSerializer
import io.xh.hoist.log.LogSupport

import javax.servlet.http.HttpServletResponse
import java.util.concurrent.ExecutionException

import static org.apache.hc.core5.http.HttpStatus.SC_BAD_REQUEST
import static org.apache.hc.core5.http.HttpStatus.SC_INTERNAL_SERVER_ERROR


/**
 * This class provides the default server-side exception handling in Hoist.
 *
 * An instance of the class is installed as an injectable Spring Bean by the framework.
 * Applications may override it on startup by specifying an alternative Bean in resources.groovy.
 *
 * This bean is automatically wired up for use in all descendants of BaseController and Timer.
 * These two contexts capture the overwhelming majority of code execution in a Hoist server.
 */
@CompileStatic
class ExceptionRenderer {

    /**
     * Sanitizes, pre-processes, and logs unhandled exception.
     *
     * Used by BaseController, ClusterRequest, Timer, and AccessInterceptor to handle
     * otherwise unhandled exception.
     *
     * @returns sanitized, processed exception, appropriate for serialization to client.
     */
    Throwable handleException(Throwable t, LogSupport logSupport, Object msg = null) {
        t = preprocess(t)
        if (msg) {
            shouldlogDebug(t) ? logSupport.logDebug(msg, t) : logSupport.logError(msg, t)
        } else {
            shouldlogDebug(t) ? logSupport.logDebug(t) : logSupport.logError(t)
        }

        return t
    }

    /**
     * Render an Exception to the  Http Response.
     */
    void renderException(Throwable t, HttpServletResponse response) {
        response.setStatus(getHttpStatus(t))
        response.setContentType('application/json')
        response.writer.write(JSONSerializer.serialize(toJSON(t)))
        response.flushBuffer()
    }

    /**
     * Produce a one-line summary string for an exception.
     *
     * The default implementation is designed to yield meaningful information within a one-line summary.
     *
     * For more detailed exception rendering, users will need to log the entire exception, typically via
     * using "TRACE" mode.
     */
    String summaryTextForThrowable(Throwable t) {
        summaryTextInternal(t, true)
    }

    Object toJSON(Throwable t) {
        t instanceof JSONFormat ?
            t :
            [
                name     : t.class.simpleName,
                message  : t.message,
                cause    : t.cause?.message,
                isRoutine: t instanceof RoutineException
            ].findAll {it.value }
    }

    //---------------------------------------------
    // Template methods.  For application override
    //---------------------------------------------
    protected Throwable preprocess(Throwable t) {
        if (t instanceof grails.validation.ValidationException) {
            t = new ValidationException(t)
        }
        GrailsUtil.deepSanitize(t)
        return t
    }

    protected boolean shouldlogDebug(Throwable t) {
        return t instanceof RoutineException
    }

    protected int getHttpStatus(Throwable t) {

        if (t instanceof HttpException && !(t instanceof ExternalHttpException)) {
            return ((HttpException) t).statusCode
        }

        return t instanceof RoutineException ?
                SC_BAD_REQUEST :
                SC_INTERNAL_SERVER_ERROR
    }

    //---------------------------
    // Implementation
    //---------------------------
    private String summaryTextInternal(Throwable t, boolean includeCause) {

        // Skip the common thin wrapper around async exceptions
        if (t instanceof ExecutionException && t.cause) {
            t = t.cause;
        }

        // Return (optional) message and class name
        def msg = t.message,
            cause = t.cause,
            name = t.class.simpleName,
            ret = msg ? "$msg [$name]" : "[$name]"

        // ...appending one level of cause, recursively. Could consider also drilling down to "root" cause.
        if (cause && includeCause) {
            ret += " caused by " + summaryTextInternal(cause, false)
        }

        return ret
    }
}
