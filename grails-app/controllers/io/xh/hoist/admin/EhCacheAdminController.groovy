/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2021 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin

import io.xh.hoist.BaseController
import io.xh.hoist.security.Access
import net.sf.ehcache.CacheManager

@Access(['HOIST_ADMIN'])
class EhCacheAdminController extends BaseController {

    def listCaches() {
        def manager = cacheManager,
            caches = []

        if (manager) {
            caches = manager.cacheNames.collect {
                def cache = manager.getCache(it)
                return [
                        name: cache.name,
                        entries: cache.size,
                        heapSize: (cache.calculateInMemorySize() / 1000000).toDouble().round(1) + 'MB',
                        evictionPolicy: cache.memoryStoreEvictionPolicy.name,
                        status: cache.status.toString()
                ]
            }    
        }

        renderJSON(caches)
    }

    def clearAllCaches() {
        def caches = cacheManager.cacheNames
        caches.each {clearCache(it)}
        renderJSON(success: true)
    }

    def clearCaches() {
        def caches = params.names instanceof String ? [params.names] : params.names
        caches.each {clearCache(it)}
        renderJSON(success: true)
    }


    //------------------------
    // Implementation
    //------------------------
    private CacheManager getCacheManager() {
        CacheManager.ALL_CACHE_MANAGERS.first()
    }

    private void clearCache(String name) {
        cacheManager.clearAllStartingWith(name)
        logInfo('Cleared hibernate cache: ' + name)
    }
    
}
