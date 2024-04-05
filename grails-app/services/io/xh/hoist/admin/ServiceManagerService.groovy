/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin

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
            prefix = svc.class.name + '_',
            timers = svc.timers*.adminStats,
            distObjs = clusterService.distributedObjects
                .findAll { it.getName().startsWith(prefix) }
                .collect {clusterAdminService.getAdminStatsForObject(it)}

        Map ret = svc.adminStats
        if (timers || distObjs) {
            ret = ret.clone()
            if (distObjs) ret.distributedObjects = distObjs
            if (timers.size() == 1) {
                ret.timer = timers[0]
            } else if (timers.size() > 1) {
                ret.timers = timers
            }
        }

        return ret
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
