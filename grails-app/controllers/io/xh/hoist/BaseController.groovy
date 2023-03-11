/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2022 Extremely Heavy Industries Inc.
 */

package io.xh.hoist

import grails.async.Promise
import grails.async.web.WebPromises
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import io.xh.hoist.exception.ExceptionRenderer
import io.xh.hoist.json.JSONSerializer
import io.xh.hoist.log.LogSupport
import io.xh.hoist.user.HoistUser
import io.xh.hoist.user.IdentityService
import io.xh.hoist.user.IdentitySupport

@Slf4j
@CompileStatic
abstract class BaseController implements IdentitySupport, LogSupport {

    IdentityService identityService
    ExceptionRenderer exceptionRenderer

    protected void renderJSON(Object o){
        response.setContentType('application/json; charset=UTF-8')
        render (JSONSerializer.serialize(o))
    }

    protected Promise runAsync(Closure c) {
        WebPromises.task {
            c.call()
        }.onError { Throwable t ->
            exceptionRenderer.handleException(t, request, response, this)
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
        exceptionRenderer.handleException(ex, request, response, this)
    }
}
