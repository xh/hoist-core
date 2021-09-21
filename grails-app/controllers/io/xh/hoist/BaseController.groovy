/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2021 Extremely Heavy Industries Inc.
 */

package io.xh.hoist


import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import io.xh.hoist.exception.ExceptionRenderer
import io.xh.hoist.json.JSONSerializer
import io.xh.hoist.log.LogSupport
import io.xh.hoist.user.HoistUser
import io.xh.hoist.user.IdentityService

@Slf4j
@CompileStatic
abstract class BaseController implements LogSupport {

    IdentityService identityService
    ExceptionRenderer exceptionRenderer

    protected void renderJSON(Object o){
        response.setContentType('application/json; charset=UTF-8')
        render (JSONSerializer.serialize(o))
    }

    protected HoistUser getUser() {
        identityService.user
    }

    protected String getUsername() {
        identityService.username
    }
    
    //-------------------
    // Implementation
    //-------------------
    void handleException(Exception ex) {
        exceptionRenderer.handleException(ex, request, response, this)
    }
}
