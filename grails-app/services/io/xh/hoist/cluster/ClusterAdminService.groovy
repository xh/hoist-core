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
import io.xh.hoist.util.Utils

import javax.cache.expiry.ExpiryPolicy
import javax.cache.expiry.Duration

import static io.xh.hoist.util.Utils.appContext
import static io.xh.hoist.util.Utils.getExceptionHandler

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
            isPrimary     : clusterService.isPrimary,
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
                    isReady: false
                ]
                if (result.value) {
                    ret << result.value
                } else {
                    exceptionHandler.handleException(
                        exception: result.exception,
                        logTo: this,
                        logMessage: "Exception getting stats for $name"
                    )
                }
                return ret
            }
    }

    Collection<Map> listObjects() {
        clusterService
            .hzInstance
            .distributedObjects
            .findAll { !(it instanceof ExecutorServiceProxy) }
            .collect { getAdminStatsForObject(it) }
    }

    void clearObjects(List<String> names) {
        def all = clusterService.distributedObjects
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

    Map getAdminStatsForObject(DistributedObject obj) {
        switch (obj) {
            case ReplicatedMap:
                def stats = obj.getReplicatedMapStats()
                return [
                    name           : obj.getName(),
                    type           : 'Replicated Map',
                    size           : obj.size(),
                    lastUpdateTime : stats.lastUpdateTime ?: null,
                    lastAccessTime : stats.lastAccessTime ?: null,

                    hits           : stats.hits,
                    gets           : stats.getOperationCount,
                    puts           : stats.putOperationCount
                ]
            case IMap:
                def stats = obj.getLocalMapStats()
                return [
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
            case ISet:
                def stats = obj.getLocalSetStats()
                return [
                    name          : obj.getName(),
                    type          : 'Set',
                    size          : obj.size(),
                    lastUpdateTime: stats.lastUpdateTime ?: null,
                    lastAccessTime: stats.lastAccessTime ?: null,
                ]
            case ITopic:
                def stats = obj.getLocalTopicStats()
                return [
                    name                 : obj.getName(),
                    type                 : 'Topic',
                    publishOperationCount: stats.publishOperationCount,
                    receiveOperationCount: stats.receiveOperationCount
                ]
            case RingbufferProxy:
                return [
                    name          : obj.getName(),
                    type          : 'RingBuffer',
                    size          : obj.size(),
                    capacity      : obj.capacity()
                ]
            case CacheProxy:
                def evictionConfig = obj.cacheConfig.evictionConfig,
                    expiryPolicy = obj.cacheConfig.expiryPolicyFactory.create(),
                    stats = obj.localCacheStatistics
                return [
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
            default:
                return [
                    name     : obj.getName(),
                    type: obj.class.toString()
                ]
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
