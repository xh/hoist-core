/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin

import io.xh.hoist.BaseService

class ServiceManagerService extends BaseService {

    def grailsApplication

    Map<String, BaseService> getServices() {
        return grailsApplication.mainContext.getBeansOfType(BaseService.class, false, false)
    }

    void clearCaches(List<String> serviceNames) {
        def allServices = getServices()

        serviceNames.each {
            def svc = allServices[it]
            if (svc) {
                svc.clearCaches()
                logInfo('Cleared service cache', it)
            }
        }
    }
}
