/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2025 Extremely Heavy Industries Inc.
 */

package io.xh.hoist

import grails.async.Promise
import groovy.transform.CompileStatic
import io.xh.hoist.cluster.ClusterService
import io.xh.hoist.cluster.ClusterResult
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
import static org.apache.hc.core5.http.HttpStatus.SC_NO_CONTENT
import static org.apache.hc.core5.http.HttpStatus.SC_OK

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
        response.contentType = 'application/json; charset=UTF-8'
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
     * Render a ClusterResult to the Request object as Json.
     *
     * If the result's value is a string, it will be assumed to be Json and rendered as is.
     * Otherwise it will be serialized as needed.  The former is the most efficient, and typical
     * use of this method; to get ClusterResults in this form, be sure to use the appropriate
     * variants of ClusterUtils, e.g. `ClusterUtils.runOnXXXAsJson`.
     */
    protected void renderClusterJSON(ClusterResult result) {
        def contentType = 'application/json; charset=UTF-8',
            exception = result.exception,
            value = result.value

        if (exception) {
            render(
                text: exception.causeAsJson,
                status: exception.causeStatusCode,
                contentType: contentType
            )
        } else {
            value != null ?
                render(
                    text: value instanceof String ? value : JSONSerializer.serialize(value),
                    status: SC_OK,
                    contentType: contentType
                ) :
                render(
                    text: null,
                    status: SC_NO_CONTENT,
                    contentType: contentType
                )
        }
    }


    /**
     * Render an empty, successful response.
     */
    protected void renderSuccess() {
        // Content type not strictly needed -- this type provides consistency with the rest of the
        // api and in particular what would be returned for an exception on the same endpoint.
        render(text: null, status: SC_NO_CONTENT, contentType: 'application/json; charset=UTF-8')
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
