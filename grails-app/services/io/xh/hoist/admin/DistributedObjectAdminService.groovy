/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2025 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.admin

import com.hazelcast.cache.impl.CacheProxy
import com.hazelcast.collection.ISet
import com.hazelcast.core.DistributedObject
import com.hazelcast.executor.impl.ExecutorServiceProxy
import com.hazelcast.map.IMap
import com.hazelcast.nearcache.NearCacheStats
import com.hazelcast.replicatedmap.ReplicatedMap
import com.hazelcast.ringbuffer.impl.RingbufferProxy
import com.hazelcast.topic.ITopic
import io.xh.hoist.BaseService
import io.xh.hoist.cluster.ClusterRequest
import io.xh.hoist.cluster.DistributedObjectInfo
import io.xh.hoist.cluster.DistributedObjectsReport

import javax.cache.expiry.Duration
import javax.cache.expiry.ExpiryPolicy

import static io.xh.hoist.util.Utils.appContext

class DistributedObjectAdminService extends BaseService {
    def grailsApplication

    DistributedObjectsReport getDistributedObjectsReport() {
        def startTimestamp = System.currentTimeMillis(),
            responsesByInstance = clusterService.submitToAllInstances(new ListDistributedObjects())
        return new DistributedObjectsReport(
            info: responsesByInstance.collectMany {it.value.value},
            startTimestamp: startTimestamp,
            endTimestamp: System.currentTimeMillis()
        )
    }

    private List<DistributedObjectInfo> listDistributedObjects() {
        // Services and their resources
        Map<String, BaseService> svcs = grailsApplication.mainContext.getBeansOfType(BaseService.class, false, false)
        def resourceObjs = svcs.collectMany { _, svc ->
            [
                // Services themselves
                getHoistInfo(svc, name: svc.class.getName(), type: 'Service'),
                // Resources, excluding those that are also DistributedObject
                *svc.resources.findAll { k, v -> !(v instanceof DistributedObject)}.collect { k, v ->
                    getHoistInfo(v, name: svc.hzName(k))
                }
            ]
        },
        // Distributed objects
            hzObjs = clusterService
                    .hzInstance
                    .distributedObjects
                    .findAll { !(it instanceof ExecutorServiceProxy) }
                    .collect { getHzInfo(it) }

        return [*hzObjs, *resourceObjs].findAll{ it } as List<DistributedObjectInfo>
    }
    static class ListDistributedObjects extends ClusterRequest<List<DistributedObjectInfo>> {
        List<DistributedObjectInfo> doCall() {
            appContext.distributedObjectAdminService.listDistributedObjects()
        }
    }

    // Clear all Hibernate caches, or a specific list of caches by name.
    void clearHibernateCaches(List<String> names = null) {
        def hibernateCaches = clusterService.distributedObjects.findAll { it instanceof CacheProxy }
        if (!names) {
            names = hibernateCaches.collect { it.getName() }
        }

        names.each { name ->
            def obj = hibernateCaches.find { it.getName() == name }
            if (obj) {
                obj.clear()
                logInfo("Cleared " + name)
            } else {
                logWarn('Cannot clear object - unsupported type', name)
            }
        }
    }

    // No named arg overloads
    DistributedObjectInfo getInfo(def obj) {
        getInfo([:], obj)
    }

    DistributedObjectInfo getHoistInfo(def obj) {
        getHoistInfo([:], obj)
    }

    DistributedObjectInfo getHzInfo(DistributedObject obj) {
        getHzInfo([:], obj)
    }

    DistributedObjectInfo getInfo(Map overrides, def obj) {
        obj instanceof DistributedObject ? getHzInfo(overrides, obj) : getHoistInfo(overrides, obj)
    }

    DistributedObjectInfo getHoistInfo(Map overrides, def obj) {
        def comparisonFields = null,
            adminStats = null,
            error = null

        try {
            comparisonFields = obj.hasProperty('comparisonFields') ? obj.comparisonFields : null
            adminStats = obj.hasProperty('adminStats') ? obj.adminStats : null
        } catch (Exception e) {
            def msg = 'Error extracting admin stats'
            logError(msg, e)
            error = "$msg | ${e.message}"
        }

        return new DistributedObjectInfo(
            comparisonFields: comparisonFields,
            adminStats: adminStats,
            error: error,
            *: overrides
        )
    }

    DistributedObjectInfo getHzInfo(Map overrides, DistributedObject obj) {
        switch (obj) {
            case ReplicatedMap:
                def stats = obj.getReplicatedMapStats()
                return new DistributedObjectInfo(
                    comparisonFields: ['size'],
                    adminStats: [
                        name          : obj.getName(),
                        type          : 'ReplicatedMap',
                        size          : obj.size(),
                        lastUpdateTime: stats.lastUpdateTime ?: null,
                        lastAccessTime: stats.lastAccessTime ?: null,

                        hits          : stats.hits,
                        gets          : stats.getOperationCount,
                        puts          : stats.putOperationCount
                    ],
                    *: overrides
                )
            case IMap:
                def stats = obj.getLocalMapStats()
                return new DistributedObjectInfo(
                    comparisonFields: ['size'],
                    adminStats: [
                        name           : obj.getName(),
                        type           : 'IMap',
                        size           : obj.size(),
                        lastUpdateTime : stats.lastUpdateTime ?: null,
                        lastAccessTime : stats.lastAccessTime ?: null,

                        ownedEntryCount: stats.ownedEntryCount,
                        hits           : stats.hits,
                        gets           : stats.getOperationCount,
                        sets           : stats.setOperationCount,
                        puts           : stats.putOperationCount,
                        nearCache      : getNearCacheStats(stats.nearCacheStats),
                    ],
                    *: overrides
                )
            case ISet:
                def stats = obj.getLocalSetStats()
                return new DistributedObjectInfo(
                    comparisonFields: ['size'],
                    adminStats: [
                        name          : obj.getName(),
                        type          : 'ISet',
                        size          : obj.size(),
                        lastUpdateTime: stats.lastUpdateTime ?: null,
                        lastAccessTime: stats.lastAccessTime ?: null,
                    ],
                    *: overrides
                )
            case ITopic:
                def stats = obj.getLocalTopicStats()
                return new DistributedObjectInfo(
                    adminStats: [
                        name                 : obj.getName(),
                        type                 : 'Topic',
                        publishOperationCount: stats.publishOperationCount,
                        receiveOperationCount: stats.receiveOperationCount
                    ],
                    *: overrides
                )
            case RingbufferProxy:
                return new DistributedObjectInfo(
                    adminStats: [
                        name    : obj.getName(),
                        type    : 'Ringbuffer',
                        size    : obj.size(),
                        capacity: obj.capacity()
                    ],
                    *: overrides
                )
            case CacheProxy:
                def evictionConfig = obj.cacheConfig.evictionConfig,
                    expiryPolicy = obj.cacheConfig.expiryPolicyFactory.create(),
                    stats = obj.localCacheStatistics
                return new DistributedObjectInfo(
                    comparisonFields: ['size'],
                    adminStats: [
                        name              : obj.getName(),
                        type              : 'Hibernate Cache',
                        size              : obj.size(),
                        lastUpdateTime    : stats.lastUpdateTime ?: null,
                        lastAccessTime    : stats.lastAccessTime ?: null,

                        ownedEntryCount   : stats.ownedEntryCount,
                        cacheHits         : stats.cacheHits,
                        cacheHitPercentage: stats.cacheHitPercentage?.round(0),
                        config            : [
                            size          : evictionConfig.size,
                            maxSizePolicy : evictionConfig.maxSizePolicy,
                            evictionPolicy: evictionConfig.evictionPolicy,
                            expiryPolicy  : formatExpiryPolicy(expiryPolicy)
                        ]
                    ],
                    *: overrides
                )
            default:
                return new DistributedObjectInfo(
                    adminStats: [
                        name: obj.getName(),
                        type: obj.class.toString()
                    ],
                    *: overrides
                )
        }
    }

    //--------------------
    // Implementation
    //--------------------
    private Map getNearCacheStats(NearCacheStats stats) {
        if (!stats) return null
        [
            ownedEntryCount    : stats.ownedEntryCount,
            lastPersistenceTime: stats.lastPersistenceTime,
            hits               : stats.hits,
            misses             : stats.misses,
            ratio              : stats.ratio.round(2)
        ]
    }

    private Map formatExpiryPolicy(ExpiryPolicy policy) {
        def ret = [:]
        if (policy.expiryForCreation) ret.creation = formatDuration(policy.expiryForCreation)
        if (policy.expiryForAccess) ret.access = formatDuration(policy.expiryForAccess)
        if (policy.expiryForUpdate) ret.update = formatDuration(policy.expiryForUpdate)
        return ret
    }


    private String formatDuration(Duration duration) {
        if (duration.isZero()) return 0
        if (duration.isEternal()) return 'eternal'
        return duration.timeUnit.toSeconds(duration.durationAmount) + 's'
    }

}
