/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2025 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin

import com.hazelcast.core.DistributedObject
import io.xh.hoist.AdminStats
import io.xh.hoist.BaseService

import static io.xh.hoist.util.Utils.isSensitiveParamName

class ServiceManagerService extends BaseService {

    def grailsApplication

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

    Map getEnvironmentProperties() {
        return [
            environment: System.getenv().collectEntries {
                [it.key, isSensitiveParamName(it.key) ? '*****' : it.value]
            },
            properties: System.properties
        ]
    }


    //----------------------
    // Implementation
    //----------------------
    private List getResourceStats(BaseService svc) {
        def ret = []
        svc.resources
            .findAll { !it.key.startsWith('xh_') }  // skip hoist implementation objects
            .each { k, v ->
                AdminStats stats = null
                if (v instanceof AdminStats) stats = v
                if (v instanceof DistributedObject) stats = new HzAdminStats(v)
                // rely on the name (key) service knows, i.e avoid HZ prefix
                if (stats) ret << [*: stats.adminStats, name: k]
            }
        return ret
    }

    private Map<String, BaseService> getServicesInternal() {
        return grailsApplication.mainContext.getBeansOfType(BaseService.class, false, false)
    }
}
