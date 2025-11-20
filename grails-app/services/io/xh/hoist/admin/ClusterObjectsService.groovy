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

import static io.xh.hoist.json.JSONParser.parseArray
import static io.xh.hoist.util.ClusterUtils.runOnAllInstancesAsJson
import static java.lang.System.currentTimeMillis

/**
 * Service to harvest and report on information about distributed objects replicated across a
 * Hazelcast cluster. Powers the Cluster > Objects tab within the Hoist Admin Console.
 *
 * "Cluster Objects" include instances of Hoist services and any {@link AdminStats} they
 * expose as well as their managed {@link BaseService#resources} such as Caches, CachedValues,
 * and Timers created via the provided factories. Info on Hazelcast's built-in DistributedObjects
 * are also included, as well as Hibernate caches.
 *
 * This service also exposes Hoist's admin API for clearing Hibernate caches.
 */
class ClusterObjectsService extends BaseService {
    def grailsApplication

    ClusterObjectsReport getClusterObjectsReport() {
        def startTimestamp = currentTimeMillis(),
            info = runOnAllInstancesAsJson(this.&listClusterObjects)
                .collectMany { it.value.exception ? [] : parseArray(it.value.value) }

        return new ClusterObjectsReport(info, startTimestamp, currentTimeMillis())
    }

    /**
     * Clear all Hibernate caches, or a specific list of caches by name.
     */
    void clearHibernateCaches(List<String> names = null) {
        def caches = clusterService.distributedObjects
            .findAll { it instanceof CacheProxy }

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
}
