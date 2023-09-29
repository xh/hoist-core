package io.xh.hoist.cluster

import com.hazelcast.cache.impl.CacheProxy
import io.xh.hoist.BaseService
import io.xh.hoist.util.Utils
import org.hibernate.SessionFactory

class HibernateAdminService extends BaseService {

    Collection<Map> listCaches() {

        def hzCaches = getHzCaches()
        listHibernateCaches()
            .collect {hzCaches[it.name]}
            .findAll()
            .collect {CacheProxy c ->
                def evictionConfig = c.cacheConfig.evictionConfig,
                    cacheStats = c.localCacheStatistics
                return [
                    name: c.name,
                    size: c.size(),
                    stats: [
                        config: [
                            size: evictionConfig.size,
                            maxSizePolicy: evictionConfig.maxSizePolicy,
                            evictionPolicy: evictionConfig.evictionPolicy
                        ],
                        stats: [
                            ownedEntryCount: cacheStats.ownedEntryCount,
                            cacheHits: cacheStats.cacheHits,
                            cacheHitPercentage: cacheStats.cacheHitPercentage?.round(0),
                            lastUpdateTime: cacheStats.lastUpdateTime
                        ]
                    ]
                ]
            }
    }

    void clearCaches(List<String> names) {
        def caches = listHibernateCaches().findAll {names.contains(it.name)}
        caches.each { c ->
            def factory = c.factory as SessionFactory,
                cache = factory.cache

            try {cache.evictEntityRegion(c.name)}     catch (e) {}
            try {cache.evictCollectionRegion(c.name)} catch (e) {}
            try {cache.evictQueryRegion(c.name)}      catch (e) {}
        }
    }

    //------------------------------
    // Implementation
    //------------------------------
    private List<Map> listHibernateCaches() {
        def appContext = Utils.appContext
        def factories = appContext.getBeanDefinitionNames()
            .findAll { it.startsWith('sessionFactory') }
            .collect { appContext.getBean(it) as SessionFactory }

        factories.collectMany { factory ->
            def stats = factory.statistics

            stats.secondLevelCacheRegionNames
                .findAll { !it.contains('org.hibernate') }
                .collect {
                    [name: it, factory: factory]
                }
        }
    }

    private Map<String, CacheProxy> getHzCaches() {
        clusterService
            .hzInstance
            .distributedObjects
            .findAll { it instanceof CacheProxy }
            .collectEntries { [it.name, it] }
    }
}
