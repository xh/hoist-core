/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2025 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.admin

import com.hazelcast.cache.impl.CacheProxy
import com.hazelcast.executor.impl.ExecutorServiceProxy
import io.xh.hoist.AdminStats
import io.xh.hoist.BaseService
import io.xh.hoist.cluster.ClusterRequest

import static io.xh.hoist.util.Utils.appContext
import static java.lang.System.currentTimeMillis

class ClusterObjectsService extends BaseService {
    def grailsApplication

    ClusterObjectsReport getClusterObjectsReport() {
        def startTimestamp = currentTimeMillis(),
            info = clusterService
                .submitToAllInstances(new ListClusterObjects())
                .collectMany { it.value.value }

        return new ClusterObjectsReport(
            info: info,
            startTimestamp: startTimestamp,
            endTimestamp: currentTimeMillis()
        )
    }

    /**
     * Clear all Hibernate caches, or a specific list of caches by name.
     */
    void clearHibernateCaches(List<String> names = null) {
        def caches = clusterService.distributedObjects.findAll {it instanceof CacheProxy}
        names ?= caches*.name
        names.each { name ->
            def obj = caches.find { it.name == name }
            if (obj) {
                obj.clear()
                logInfo('Cleared ' + name)
            } else {
                logWarn('Cannot find cache', name)
            }
        }
    }

    //--------------------
    // Implementation
    //--------------------
    private List<ClusterObjectInfo> listClusterObjects() {
        // Services and their AdminStat implementing resources
        Map<String, BaseService> svcs = grailsApplication.mainContext.getBeansOfType(BaseService.class, false, false)
        def hoistObjs = svcs.collectMany { _, svc ->
            [
                new ClusterObjectInfo(name: svc.class.name, type: 'Service', target: svc),
                *svc.resources
                    .findAll { k, v -> v instanceof AdminStats }
                    .collect { k, v -> new ClusterObjectInfo(name: svc.hzName(k), target: v) }
            ]
        }

        // Hazelcast built-ins
        def hzObjs = clusterService
            .hzInstance
            .distributedObjects
            .findAll { !(it instanceof ExecutorServiceProxy) }
            .collect { new ClusterObjectInfo(target: new HzAdminStats(it)) }

        return (hzObjs + hoistObjs) as List<ClusterObjectInfo>
    }

    static class ListClusterObjects extends ClusterRequest<List<ClusterObjectInfo>> {
        List<ClusterObjectInfo> doCall() {
            appContext.clusterObjectsService.listClusterObjects()
        }
    }
}
