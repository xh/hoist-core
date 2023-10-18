package io.xh.hoist.cluster

import com.hazelcast.cache.impl.CacheProxy
import com.hazelcast.collection.ISet
import com.hazelcast.core.DistributedObject
import com.hazelcast.executor.impl.ExecutorServiceProxy
import com.hazelcast.map.IMap
import com.hazelcast.nearcache.NearCacheStats
import com.hazelcast.replicatedmap.ReplicatedMap
import com.hazelcast.topic.ITopic
import io.xh.hoist.BaseService
import io.xh.hoist.util.Utils

import java.util.concurrent.Callable

import static io.xh.hoist.util.Utils.appContext

/**
 * Reports on all instances within the current Hazelcast cluster, including a general list of all
 * live instances with summary stats/metrics for each + more detailed information on distributed
 * objects as seen by all instances, or any particular instance.
 */
class ClusterAdminService extends BaseService {

    Map getAdminStats() {
        return [
            name          : clusterService.instanceName,
            address       : clusterService.localMember.address.toString(),
            isMaster      : clusterService.isMaster,
            isReady       : clusterService.isReady,
            memory        : appContext.memoryMonitoringService.latestSnapshot,
            connectionPool: appContext.connectionPoolMonitoringService.latestSnapshot,
            wsConnections : appContext.webSocketService.allChannels.size(),
            startupTime   : Utils.startupTime
        ]
    }

    static class AdminStatsTask extends ClusterRequest {
        def doCall() {
            return appContext.clusterAdminService.adminStats
        }
    }

    Collection<Map> getAllStats() {
        clusterService.submitToAllInstances(new AdminStatsTask())
            .collect { name, result ->
                def ret = [
                    name   : name,
                    isLocal: name == clusterService.instanceName,
                    isReady: false
                ]
                if (result.value) {
                    ret << result.value
                }
                return ret
            }
    }

    Collection<Map> listObjects() {
        clusterService
            .hzInstance
            .distributedObjects
            .findAll { !(it instanceof ExecutorServiceProxy) }
            .collect { getObjectData(it) }
    }

    void clearObjects(List<String> names) {
        def all = clusterService.hzInstance.distributedObjects
        names.each { name ->
            def obj = all.find { it.getName() == name }
            if (obj instanceof ReplicatedMap ||
                obj instanceof IMap ||
                obj instanceof CacheProxy ||
                obj instanceof ISet
            ) {
                obj.clear()
                logInfo("Cleared " + name)
            } else {
                logWarn('Cannot clear object - unsupported type', name)
            }
        }
    }

    void clearHibernateCaches() {
        appContext.beanDefinitionNames
            .findAll { it.startsWith('sessionFactory') }
            .each { appContext.getBean(it)?.cache.evictAllRegions() }
    }

    //--------------------
    // Implementation
    //--------------------
    private Map getObjectData(DistributedObject obj) {
        def ret = [
            name      : obj.getName(),
            objectType: 'Unknown',
            stats     : [:],
            lastUpdateTime: null,
            lastAccessTime: null,
            size      : null
        ]

        switch (obj) {
            case ReplicatedMap:
                def stats = obj.getReplicatedMapStats()
                ret << [
                    objectType    : 'Replicated Map',
                    size          : obj.size(),
                    lastUpdateTime: stats.lastUpdateTime ?: null,
                    lastAccessTime: stats.lastAccessTime ?: null,
                    stats         : [
                        ownedEntryCount: stats.ownedEntryCount,
                        heapCost       : stats.heapCost,
                        hits           : stats.hits,
                        gets           : stats.getOperationCount,
                        puts           : stats.putOperationCount,
                        creationTime   : stats.creationTime
                    ]
                ]
                break
            case IMap:
                def stats = obj.getLocalMapStats()
                ret << [
                    objectType    : 'Distributed Map',
                    size          : obj.size(),
                    lastUpdateTime: stats.lastUpdateTime ?: null,
                    lastAccessTime: stats.lastAccessTime ?: null,
                    stats         : [
                        ownedEntryCount: stats.ownedEntryCount,
                        heapCost       : stats.heapCost,
                        hits           : stats.hits,
                        gets           : stats.getOperationCount,
                        sets           : stats.setOperationCount,
                        puts           : stats.putOperationCount,
                        nearCache      : getNearCacheStats(stats.nearCacheStats),
                        creationTime   : stats.creationTime,
                    ]
                ]
                break
            case ISet:
                def stats = obj.getLocalSetStats()
                ret << [
                    objectType    : 'Set',
                    size          : obj.size(),
                    lastUpdateTime: stats.lastUpdateTime ?: null,
                    lastAccessTime: stats.lastAccessTime ?: null,
                    stats         : [
                        creationTime: stats.creationTime
                    ]
                ]
                break
            case ITopic:
                def stats = obj.getLocalTopicStats()
                ret << [
                    objectType: 'Topic',
                    stats     : [
                        publishOperationCount: stats.publishOperationCount,
                        receiveOperationCount: stats.receiveOperationCount,
                        creationTime         : stats.creationTime
                    ]
                ]
                break
            case CacheProxy:
                def evictionConfig = obj.cacheConfig.evictionConfig,
                    stats = obj.localCacheStatistics
                ret << [
                    objectType    : 'Cache',
                    size          : obj.size(),
                    lastUpdateTime: stats.lastUpdateTime ?: null,
                    lastAccessTime: stats.lastAccessTime ?: null,
                    stats: [
                        config            : [
                            size          : evictionConfig.size,
                            maxSizePolicy : evictionConfig.maxSizePolicy,
                            evictionPolicy: evictionConfig.evictionPolicy
                        ],
                        ownedEntryCount   : stats.ownedEntryCount,
                        cacheHits         : stats.cacheHits,
                        cacheHitPercentage: stats.cacheHitPercentage?.round(0),
                        creationTime      : stats.creationTime
                    ]
                ]
                break
            default:
                ret << [
                    stats: [
                        className: obj.class.toString()
                    ]
                ]
        }
        return ret
    }

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
}
