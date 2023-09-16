package io.xh.hoist.cluster

import io.xh.hoist.BaseService
import io.xh.hoist.util.Utils
import org.hibernate.SessionFactory

class HibernateAdminService extends BaseService {

    Collection<Map> listCaches() {
        def cacheManager = clusterService.hzInstance.cacheManager
        listCachesInternal().collect { c ->
            def cache = cacheManager.getCache(c.name)
            [
                name: c.name,
                size: cache.size()
            ]
        }

        //clusterService.instance.distributedObjects.each {
        //    if (it instanceof CacheProxy) {
        //        def name = it.getName()
        //        CacheStatistics stats = it.getLocalCacheStatistics()
        //        ret << [
        //            name: name,
        //            size: it.size(),
        //            stats: [
        //                ownedEntryCount: stats.ownedEntryCount,
        //                hitPercentage: stats.cacheHitPercentage,
        //                creationTime: stats.creationTime,
        //                lastUpdateTime: stats.lastUpdateTime,
        //                lasAccessTime: stats.lastAccessTime
        //            ]
        //        ]
        //    }
        //}
    }

    void clearCaches(List<String> names) {
        def caches = listCachesInternal().findAll {names.contains(it.name)}
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
    private List<Map> listCachesInternal() {
        def appContext = Utils.appContext
        def factories = appContext.getBeanDefinitionNames()
            .findAll { it.startsWith('sessionFactory') }
            .collect { appContext.getBean(it) as SessionFactory }

        factories.collectMany { factory ->
            def stats = factory.statistics

            stats.secondLevelCacheRegionNames
                .findAll { !it.contains('org.hibernate') }
                .collect { [name: it, factory: factory] }
        }
    }
}
