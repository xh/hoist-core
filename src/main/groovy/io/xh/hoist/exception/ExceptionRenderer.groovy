/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2019 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.exception

import grails.gsp.PageRenderer
import grails.util.GrailsUtil
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import io.xh.hoist.json.JSONFormat
import io.xh.hoist.json.JSONSerializer
import io.xh.hoist.util.Utils

import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

import static org.apache.http.HttpStatus.SC_FORBIDDEN
import static org.apache.http.HttpStatus.SC_INTERNAL_SERVER_ERROR

@CompileStatic
@Slf4j
class ExceptionRenderer {

    public template = null

    /**
     * Main entry point.  Render an exception to the response.
     */
    void render(Throwable t, HttpServletRequest request, HttpServletResponse response) {
        GrailsUtil.deepSanitize(t)
        response.setStatus(getHttpStatus(t))
        switch (getResponseFormat(request)) {
            case 'HTML':
                renderAsHTML(t, request, response)
                break
            case 'JSON':
                renderAsJSON(t, request, response)
                break
        }
        response.flushBuffer()
    }

    //---------------------------------------------
    // Template methods.  For Application override
    //---------------------------------------------
    protected void renderAsJSON(Throwable t, HttpServletRequest request, HttpServletResponse response) {
        response.setContentType('application/json')
        response.writer.write(toJSON(t))
    }

    protected void renderAsHTML(Throwable t, HttpServletRequest request, HttpServletResponse response) {
        response.setContentType('text/html')
        def isAuth = getHttpStatus(t) == SC_FORBIDDEN,
            template = isAuth ? '/notAuthorized' : '/exception',
            model = [
                title: isAuth ? 'Not Authorized' : 'Exception',
                exception: t,
                exceptionAsJSON: toJSON(t)
        ]

        PageRenderer renderer = (PageRenderer) Utils.appContext.getBean('groovyPageRenderer')
        String str = renderer.render(model: model, view: template)
        response.writer.write(str)
    }

    protected int getHttpStatus(Throwable t) {
        if (t instanceof NotAuthorizedException) return SC_FORBIDDEN
        return SC_INTERNAL_SERVER_ERROR
    }

    protected String getResponseFormat(HttpServletRequest r) {
        return r.getHeader('ACCEPT')?.contains('html') ? 'HTML' : 'JSON'
    }

    protected String toJSON(Throwable t) {
        def ret = t instanceof JSONFormat ?
                t :
                [
                        name   : t.class.simpleName,
                        message: t.message,
                        cause  : t.cause?.message
                ].findAll {it.value}
        return JSONSerializer.serialize(ret);
    }
    
}
