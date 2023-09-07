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

    Collection<Map> listServices() {
        getServicesInternal().collect { name, svc ->
            return [
                name: name,
                initializedDate: svc.initializedDate,
                lastCachesCleared: svc.lastCachesCleared,
                stats: svc.adminStats
            ]
        }
    }

    void clearCaches(List<String> names) {
        def allServices = getServicesInternal()

        names.each {
            def svc = allServices[it]
            if (svc) {
                svc.clearCaches()
                logInfo('Cleared service cache', it)
            }
        }
    }

    private Map<String, BaseService> getServicesInternal() {
        return grailsApplication.mainContext.getBeansOfType(BaseService.class, false, false)
    }
}
