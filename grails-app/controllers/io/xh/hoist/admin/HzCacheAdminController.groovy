/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2022 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.admin

import io.xh.hoist.BaseController
import io.xh.hoist.security.Access
import io.xh.hoist.util.Utils

import com.hazelcast.core.Hazelcast
import org.hibernate.SessionFactory
import com.hazelcast.config.Config

import javax.cache.Caching

@Access(['HOIST_ADMIN_READER'])
class HzCacheAdminController extends BaseController {

    def clusterService

    def cacheDiagnostics() {
        def cacheManager = clusterService.instance.cacheManager,
            config = clusterService.instance.config,
            ret = listCaches().collect { c ->
                def name = c.name,
                    cacheConfig = config.getCacheConfig(name),
                    cache = cacheManager.getCache(name),
                    size = cache.size()
                [
                    name      : name,
                    localCacheStatistics: size
                    //evictionConfig: cacheConfig.evictionConfig.toString(),
                    //expiryPolicy: cacheConfig.expiryPolicyFactoryConfig.toString()
                ]
            }
        renderJSON(ret)
    }

    @Access(['HOIST_ADMIN'])
    def clearAllCaches() {
        factories.each {
            it.cache.evictAllRegions()
        }
        renderJSON(success: true)
    }

    @Access(['HOIST_ADMIN'])
    def clearGormCaches(String domainClassName) {
        List<String> cacheNames = listCaches().findAll {it.name.startsWith(domainClass.name)}*.name
        clearCaches(cacheNames)
        renderJSON(success: true)
    }

    //------------------------------
    // Implementation
    //------------------------------
    private void clearCaches(List<String> names) {
        def caches = listCaches().findAll {names.contains(it.name)}
            caches.each {c ->
                def name = c.name,
                    factory = c.factory as SessionFactory,
                    cache = factory.cache

            // Brute force this, until we can identify which type of cache it is
            cache.evictAllRegions()
            //try {cache.evictEntityRegion(name)}     catch (e) {}
            //try {cache.evictCollectionRegion(name)} catch (e) {}
            //try {cache.evictQueryRegion(name)}      catch (e) {}
        }
    }

    private List<SessionFactory> getFactories() {
        def appContext = Utils.appContext
        appContext.getBeanDefinitionNames()
            .findAll {it.startsWith('sessionFactory')}
            .collect {appContext.getBean(it)}
    }

    private List<Map> listCaches() {
        factories.collectMany {factory ->
            def stats = factory.statistics

            stats.secondLevelCacheRegionNames
                .findAll {!it.contains('org.hibernate')}
                .collect {[name: it, factory: factory]}
        }
    }

    private Config hazelcastConfig() {
        def instance = Hazelcast.allHazelcastInstances.find {
            logInfo(it.config.clusterName)
            return it.config.clusterName.startsWith(Utils.appName)
        }
        return instance.config
    }
}