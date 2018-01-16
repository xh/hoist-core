/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2018 Extremely Heavy Industries Inc.
 */

package io.xh.hoist

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import io.xh.hoist.exception.ExceptionRenderer
import io.xh.hoist.exception.NotAuthorizedException
import io.xh.hoist.json.JSON
import io.xh.hoist.log.LogSupport
import io.xh.hoist.user.HoistUser
import io.xh.hoist.user.IdentityService

@Slf4j
@CompileStatic
abstract class BaseController implements LogSupport {

    IdentityService identityService
    ExceptionRenderer exceptionRenderer

    protected void renderJSON(Object o){
        response.setContentType('application/json')
        render (new JSON(o))
    }

    protected HoistUser getUser() {
        return identityService.getUser()
    }

    protected String getUsername() {
        return getUser()?.username
    }
    
    
    //-------------------
    // Implementation
    //-------------------
    void handleException(Exception ex){
        if (!ex instanceof NotAuthorizedException) {
            log.warn(ex.message, ex)  // Problematic requests may or may not be 'errors' or log-worthy
        }
        exceptionRenderer.render(ex, request, response)
    }
    
}
