/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2019 Extremely Heavy Industries Inc.
 */

package io.xh.hoist

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import io.xh.hoist.exception.ExceptionRenderer
import io.xh.hoist.exception.RoutineException
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
        return identityService.getUser()
    }

    protected String getUsername() {
        return getUser()?.username
    }
    
    //-------------------
    // Implementation
    //-------------------
    void handleException(Exception ex) {
        def message = ex.message ?: 'Exception'
        if (ex instanceof RoutineException) {
            logErrorCompact(message,  ex)
        } else {
            log.error(message, ex) 
        }
        exceptionRenderer.render(ex, request, response)
    }
}
