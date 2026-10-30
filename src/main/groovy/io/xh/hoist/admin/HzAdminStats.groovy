/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
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
import io.xh.hoist.log.LogSupport
import io.xh.hoist.util.Utils
import org.hibernate.SessionFactory
import org.hibernate.engine.spi.SessionFactoryImplementor
import org.hibernate.persister.entity.AbstractEntityPersister
import org.hibernate.stat.CacheRegionStatistics
import org.hibernate.stat.Statistics

import java.util.regex.Pattern

import javax.cache.expiry.Duration
import javax.cache.expiry.ExpiryPolicy

/**
 * Admin stats for Hazelcast built-in objects.
 */
class HzAdminStats implements AdminStats, LogSupport {

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
                    hibernate         : getHibernateStats(obj.getName()),
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
    /**
     * Hibernate's own counters for a cache region, as distinct from the Hazelcast-level stats
     * above. Hibernate's read-write strategy reads a key before every put, so the Hazelcast hit
     * count also includes bookkeeping reads that precede writes. Hibernate only counts a hit when
     * an entity, collection, or query result is actually served from the cache.
     *
     * Entity regions also report the query cache activity of queries against the entity's table,
     * since query cache results live in a single shared region and are otherwise only visible in
     * aggregate.
     */
    private Map getHibernateStats(String regionName) {
        def sessionFactory = Utils.appContext.sessionFactory as SessionFactory,
            hibStats = sessionFactory.statistics

        if (!hibStats.statisticsEnabled) return null

        try {
            switch (regionName) {
                case 'default-update-timestamps-region':
                    return formatHibernateStats(
                        hibStats.updateTimestampsCacheHitCount,
                        hibStats.updateTimestampsCacheMissCount,
                        hibStats.updateTimestampsCachePutCount
                    )
                case 'default-query-results-region':
                    return formatHibernateStats(hibStats.getQueryRegionStatistics(regionName))
                default:
                    return [
                        *: formatHibernateStats(hibStats.getDomainDataRegionStatistics(regionName)),
                        queries: getQueryStats(sessionFactory, hibStats, regionName)
                    ]
            }
        } catch (Exception e) {
            logWarn("Failed to read Hibernate stats for cache '$regionName'", e)
            return null
        }
    }

    /**
     * Query cache counts summed over queries against the given entity's table. Criteria queries,
     * which back every GORM finder, are keyed in Hibernate's statistics by their SQL, and HQL
     * queries by their HQL, so a query is matched on either the table name or the entity name.
     * Null for collection regions and for entities with no query cache activity.
     */
    private Map getQueryStats(SessionFactory sessionFactory, Statistics hibStats, String entityName) {
        String tableName
        try {
            def persister = (sessionFactory as SessionFactoryImplementor).metamodel.entityPersister(entityName)
            tableName = (persister as AbstractEntityPersister).tableName
        } catch (Exception ignored) {
            return null
        }

        Pattern tablePattern = Pattern.compile(/\b(from|join)\s+${Pattern.quote(tableName)}\b/, Pattern.CASE_INSENSITIVE)
        long hits = 0, misses = 0, puts = 0
        hibStats.queries.each { String query ->
            if (!tablePattern.matcher(query).find() && !query.contains(entityName)) return
            def stats = hibStats.getQueryStatistics(query)
            hits += stats.cacheHitCount
            misses += stats.cacheMissCount
            puts += stats.cachePutCount
        }
        return (hits || misses || puts) ? formatHibernateStats(hits, misses, puts) : null
    }

    private Map formatHibernateStats(CacheRegionStatistics stats) {
        stats ? formatHibernateStats(stats.hitCount, stats.missCount, stats.putCount) : null
    }

    private Map formatHibernateStats(long hits, long misses, long puts) {
        def reads = hits + misses
        [
            hits         : hits,
            misses       : misses,
            puts         : puts,
            hitPercentage: reads ? (hits * 100 / reads).round(0) : null
        ]
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
