/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2019 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.exception

import grails.util.GrailsUtil
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import io.xh.hoist.json.JSONFormat
import io.xh.hoist.json.JSONSerializer

import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

import static org.apache.http.HttpStatus.SC_FORBIDDEN
import static org.apache.http.HttpStatus.SC_INTERNAL_SERVER_ERROR
import static org.apache.http.HttpStatus.SC_NOT_FOUND

@CompileStatic
@Slf4j
class ExceptionRenderer {

    /**
     * Main entry point.  Render an exception to the response.
     */
    void render(Throwable t, HttpServletRequest request, HttpServletResponse response) {
        GrailsUtil.deepSanitize(t)
        response.setStatus(getHttpStatus(t))
        renderAsJSON(t, request, response)
        response.flushBuffer()
    }

    //---------------------------------------------
    // Template methods.  For Application override
    //---------------------------------------------
    protected void renderAsJSON(Throwable t, HttpServletRequest request, HttpServletResponse response) {
        response.setContentType('application/json')
        response.writer.write(toJSON(t))
    }

    protected int getHttpStatus(Throwable t) {
        if (t instanceof NotAuthorizedException) return SC_FORBIDDEN
        return SC_INTERNAL_SERVER_ERROR
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
}
