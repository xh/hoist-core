/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2025 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin

import com.hazelcast.core.DistributedObject
import io.xh.hoist.BaseService

class ServiceManagerService extends BaseService {

    def grailsApplication,
        clusterAdminService

    Collection<Map> listServices() {
        getServicesInternal().collect { name, svc ->
            return [
                name: name,
                initializedDate: svc.initializedDate,
                lastCachesCleared: svc.lastCachesCleared
            ]
        }
    }

    Map getStats(String name) {
        def svc = grailsApplication.mainContext.getBean(name),
            resources = getResourceStats(svc)
        return resources ? [*: svc.adminStats, resources: resources] : svc.adminStats
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

    //----------------------
    // Implementation
    //----------------------
    private List getResourceStats(BaseService svc) {
        svc.resources
            .findAll { !it.key.startsWith('xh_') }  // skip hoist implementation objects
            .collect { k, v ->
                Map stats = v instanceof DistributedObject ?
                    clusterAdminService.getAdminStatsForObject(v) :
                    v.adminStats

                // rely on the name (key) service knows, i.e avoid HZ prefix
                return [*: stats, name: k]
            }
    }


    private Map<String, BaseService> getServicesInternal() {
        return grailsApplication.mainContext.getBeansOfType(BaseService.class, false, false)
    }
}
