/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2021 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin

import io.xh.hoist.BaseController
import io.xh.hoist.BaseService
import io.xh.hoist.security.Access

@Access(['HOIST_ADMIN'])
class ServiceAdminController extends BaseController {

    def listServices() {
        def ret = getServices().collect{[name: it.key]}
        renderJSON(ret)
    }

    def clearCaches() {
        def allServices = getServices(),
            services = params.names instanceof String ? [params.names] : params.names

        services.each {
            def svc = allServices[it]
            if (svc) {
                svc.clearCaches()
                logInfo('Cleared service cache', it)
            }

        }
        renderJSON(success: true)
    }


    //------------------------
    // Implementation
    //------------------------
    private Map getServices() {
        return grailsApplication.mainContext.getBeansOfType(BaseService.class, false, false)
    }
    
}
