package io.xh.hoist.admin

import com.hazelcast.cache.impl.CacheProxy
import com.hazelcast.collection.ISet
import com.hazelcast.core.DistributedObject
import com.hazelcast.map.IMap
import com.hazelcast.nearcache.NearCacheStats
import com.hazelcast.replicatedmap.ReplicatedMap
import com.hazelcast.ringbuffer.impl.RingbufferProxy
import com.hazelcast.topic.ITopic
import io.xh.hoist.AdminStats

import javax.cache.expiry.Duration
import javax.cache.expiry.ExpiryPolicy

import static java.util.Collections.emptyList
import static java.util.Collections.emptyMap

/**
 * Admin stats for Hazelcast built-in objects.
 */
class HzAdminStats implements AdminStats {

    private Map _stats
    private List<String> _comparables

    Map getAdminStats() {
        _stats
    }

    List<String> getComparableAdminStats() {
        _comparables
    }

    HzAdminStats(DistributedObject obj) {
        switch (obj) {
            case ReplicatedMap:
                def stats = obj.getReplicatedMapStats()
                _comparables = ['size']
                _stats = [
                    name          : obj.getName(),
                    type          : 'ReplicatedMap',
                    size          : obj.size(),
                    lastUpdateTime: stats.lastUpdateTime ?: null,
                    lastAccessTime: stats.lastAccessTime ?: null,

                    hits          : stats.hits,
                    gets          : stats.getOperationCount,
                    puts          : stats.putOperationCount
                ]
                break
            case IMap:
                def stats = obj.getLocalMapStats()
                _comparables = ['size']
                _stats = [
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
                    nearCache      : getNearCacheStats(stats.nearCacheStats)
                ]
                break
            case ISet:
                def stats = obj.getLocalSetStats()
                _comparables = ['size']
                _stats = [
                    name          : obj.getName(),
                    type          : 'ISet',
                    size          : obj.size(),
                    lastUpdateTime: stats.lastUpdateTime ?: null,
                    lastAccessTime: stats.lastAccessTime ?: null
                ]
                break
            case ITopic:
                def stats = obj.getLocalTopicStats()
                _comparables = []
                _stats = [
                    name                 : obj.getName(),
                    type                 : 'Topic',
                    publishOperationCount: stats.publishOperationCount,
                    receiveOperationCount: stats.receiveOperationCount
                ]
                break
            case RingbufferProxy:
                _comparables = []
                _stats = [
                    name    : obj.getName(),
                    type    : 'Ringbuffer',
                    size    : obj.size(),
                    capacity: obj.capacity()
                ]
                break
            case CacheProxy:
                def evictionConfig = obj.cacheConfig.evictionConfig,
                    expiryPolicy = obj.cacheConfig.expiryPolicyFactory.create(),
                    stats = obj.localCacheStatistics
                _comparables = ['size']
                _stats = [
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
                break
            default:
                _comparables = []
                _stats = [
                    name: obj.getName(),
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
