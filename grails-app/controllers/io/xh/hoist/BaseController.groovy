/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2025 Extremely Heavy Industries Inc.
 */

package io.xh.hoist

import grails.async.Promise
import groovy.transform.CompileStatic
import io.xh.hoist.cluster.ClusterResult
import io.xh.hoist.cluster.ClusterService
import io.xh.hoist.exception.ExceptionHandler
import io.xh.hoist.json.JSONParser
import io.xh.hoist.json.JSONSerializer
import io.xh.hoist.log.LogSupport
import io.xh.hoist.user.HoistUser
import io.xh.hoist.user.IdentityService
import io.xh.hoist.user.IdentitySupport
import org.owasp.encoder.Encode
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import static grails.async.web.WebPromises.task

@CompileStatic
abstract class BaseController implements LogSupport, IdentitySupport {

    IdentityService identityService
    ClusterService clusterService
    ExceptionHandler xhExceptionHandler

    /**
     * Render an object to JSON.
     *
     * Favor this method over the direct use of grails `render` method in order
     * to utilize the customizable jackson-based serialization exposed by Hoist.
     *
     * @param o - object to be serialized.
     */
    protected void renderJSON(Object o){
        response.setContentType('application/json; charset=UTF-8')
        render (JSONSerializer.serialize(o))
    }

    /**
     * Parse JSON submitted in the body of the request.
     *
     * Favor this method over the direct use of grails' request.getJSON() in order
     * to utilize the customizable jackson-based parsing provided by Hoist.
     *
     * @param  options.safeEncode, boolean.  True to run input through OWASP encoder before parsing.
     */
    protected Map parseRequestJSON(Map options = [:]) {
        options.safeEncode ?
            JSONParser.parseObject(safeEncode(request.inputStream.text)) :
            JSONParser.parseObject(request.inputStream)
    }

    /**
     * Parse JSON submitted in the body of the request.
     *
     * Favor this method over the direct use of grails' request.getJSON() in order
     * to utilize the customizable jackson-based parsing provided by Hoist.
     *
     * @param  options.safeEncode, boolean.  True to run input through OWASP encoder before parsing.
     */
    protected List parseRequestJSONArray(Map options = [:]) {
        options.safeEncode ?
            JSONParser.parseArray(safeEncode(request.inputStream.text)) :
            JSONParser.parseArray(request.inputStream)
    }

    /**
     * Run user-provided string input through an OWASP-provided encoder to escape tags. Note the
     * use of `forHtmlContent()` encodes only `&<>` and in particular leaves quotes un-escaped to
     * support JSON strings.
     */
    protected String safeEncode(String input) {
        return input ? Encode.forHtmlContent(input) : input
    }

    /**
     * Render a JSON ClusterResult to the Request object
     *
     * Note - ClusterResult should be created with flag 'asJson'
     */
    protected String renderClusterJSON(ClusterResult result) {
        def ret = result.value as Map
        response.setContentType('application/json; charset=UTF-8')
        response.setStatus(ret.httpStatus as Integer)
        render(ret.json)
    }

    protected Promise runAsync(Closure c) {
        task {
            try {
                c.call()
            } catch (Throwable t) {
                handleUncaughtInternal(t)
            }
        }
    }

    HoistUser getUser()         {identityService.user}
    String getUsername()        {identityService.username}
    HoistUser getAuthUser()     {identityService.authUser}
    String getAuthUsername()    {identityService.authUsername}

    //-------------------
    // Implementation
    //-------------------
    void handleException(Exception ex) {
        handleUncaughtInternal(ex)
    }

    private void handleUncaughtInternal(Throwable t) {
        xhExceptionHandler.handleException(
            exception: t,
            logTo: this,
            logMessage: [_action: actionName],
            renderTo: response
        )
    }

    // Provide cached logger to LogSupport for possible performance benefit
    private final Logger _log = LoggerFactory.getLogger(this.class)
    Logger getInstanceLog() { _log }
}
