package io.xh.hoist.cluster

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

import javax.cache.expiry.Duration
import javax.cache.expiry.ExpiryPolicy

import static io.xh.hoist.util.Utils.getAppContext

class ClusterConsistencyCheckService extends BaseService {
    def grailsApplication

    DistributedObjectsReport getDistributedObjectsReport() {
        def responsesByInstance = clusterService.submitToAllInstances(new ListDistributedObjects())
        return new DistributedObjectsReport(
            info: responsesByInstance.collectMany {it.value.value},
            timestamp: System.currentTimeMillis()
        )
    }

    private List<DistributedObjectInfo> listDistributedObjects() {
        Map<String, BaseService> svcs = grailsApplication.mainContext.getBeansOfType(BaseService.class, false, false)
        def resourceObjs = svcs.collectMany { _, svc ->
            def svcName = svc.class.getName()
            svc.resources.findAll { k, v -> !(v instanceof DistributedObject)}.collect { k, v ->
                new DistributedObjectInfo(
                    name            : svc.hzName(k),
                    comparisonFields: v.hasProperty('comparisonFields') ? v.comparisonFields : null,
                    adminStats      : v.hasProperty('adminStats') ? v.adminStats : null,
                    owner           : svcName
                )
            }
        },
            hzObjs = clusterService
                .hzInstance
                .distributedObjects
                .findAll { !(it instanceof ExecutorServiceProxy) }
                .collect { getInfoForObject(it) }

        return [*hzObjs, *resourceObjs]
    }
    static class ListDistributedObjects extends ClusterRequest<List<DistributedObjectInfo>> {
        List<DistributedObjectInfo> doCall() {
            appContext.clusterConsistencyCheckService.listDistributedObjects()
        }
    }

    // ------------------------------
    // Implementation
    // ------------------------------
    DistributedObjectInfo getInfoForObject(DistributedObject obj) {
        switch (obj) {
            case ReplicatedMap:
                def stats = obj.getReplicatedMapStats()
                return new DistributedObjectInfo(
                    name: obj.getName(),
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
                    ]
                )
            case IMap:
                def stats = obj.getLocalMapStats()
                return new DistributedObjectInfo(
                    name: obj.getName(),
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
                    ]
                )
            case ISet:
                def stats = obj.getLocalSetStats()
                return new DistributedObjectInfo(
                    name: obj.getName(),
                    comparisonFields: ['size'],
                    adminStats: [
                        name          : obj.getName(),
                        type          : 'ISet',
                        size          : obj.size(),
                        lastUpdateTime: stats.lastUpdateTime ?: null,
                        lastAccessTime: stats.lastAccessTime ?: null,
                    ]
                )
            case ITopic:
                def stats = obj.getLocalTopicStats()
                return new DistributedObjectInfo(
                    name: obj.getName(),
                    adminStats: [
                        name                 : obj.getName(),
                        type                 : 'Topic',
                        publishOperationCount: stats.publishOperationCount,
                        receiveOperationCount: stats.receiveOperationCount
                    ]
                )
            case RingbufferProxy:
                return new DistributedObjectInfo(
                    name: obj.getName(),
                    adminStats: [
                        name    : obj.getName(),
                        type    : 'Ringbuffer',
                        size    : obj.size(),
                        capacity: obj.capacity()
                    ]
                )
            case CacheProxy:
                def evictionConfig = obj.cacheConfig.evictionConfig,
                    expiryPolicy = obj.cacheConfig.expiryPolicyFactory.create(),
                    stats = obj.localCacheStatistics
                return new DistributedObjectInfo(
                    name: obj.getName(),
                    owner: 'Hibernate',
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
                    ]
                )
            default:
                return new DistributedObjectInfo(
                    name: obj.getName(),
                    adminStats: [
                        name: obj.getName(),
                        type: obj.class.toString()
                    ]
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
