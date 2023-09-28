package io.xh.hoist.cluster

import com.hazelcast.cache.CacheStatistics
import com.hazelcast.cache.impl.CacheProxy
import io.xh.hoist.BaseService
import io.xh.hoist.util.Utils
import org.hibernate.SessionFactory

class HibernateAdminService extends BaseService {

    Collection<Map> listCaches() {

        // Get the underlying cache objects
        def caches = clusterService
            .hzInstance
            .distributedObjects
            .findAll { it instanceof CacheProxy }
            .collectEntries {
                log.info('hi' + it.name)
                [it.name, it]
            }

        listCachesInternal()
            .collect {
                log.info(it.name)
                caches[it.name]
            }
            .findAll()
            .collect {CacheProxy c -> [
                name: c.name,
                size: c.size()
            ]}
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
                .collect {
                    [name: it, factory: factory]
                }
        }
    }
}
