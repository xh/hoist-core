/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2020 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.exception

import grails.util.GrailsUtil
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import io.xh.hoist.json.JSONFormat
import io.xh.hoist.json.JSONSerializer
import io.xh.hoist.log.LogSupport

import javax.servlet.http.HttpServletRequest
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
@Slf4j
class ExceptionRenderer {

    /**
     * Main entry point for request based code (e.g. controllers)
     */
    void handleException(Throwable t, HttpServletRequest request, HttpServletResponse response, LogSupport logSupport) {
        t = preprocess(t)
        logException(t, logSupport)

        response.setStatus(getHttpStatus(t))
        response.setContentType('application/json')
        response.writer.write(toJSON(t))
        response.flushBuffer()
    }

    /**
     * Main entry point for non-request based code (e.g. Timers)
     */
    void handleException(Throwable t, LogSupport logSupport) {
        t = preprocess(t)
        logException(t, logSupport)
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

    protected void logException(Throwable t, LogSupport logSupport) {
        if (shouldLogDebugCompact(t)) {
            logSupport.logDebugCompact(null, t)
        } else {
            logSupport.logErrorCompact(null, t)
        }
    }

    protected boolean shouldLogDebugCompact(Throwable t) {
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

    protected String toJSON(Throwable t) {
        def ret = t instanceof JSONFormat ?
                t :
                [
                        name   : t.class.simpleName,
                        message: t.message,
                        cause  : t.cause?.message,
                        isRoutine: t instanceof RoutineException
                ].findAll {it.value}
        return JSONSerializer.serialize(ret);
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
