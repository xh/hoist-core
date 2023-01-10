/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2022 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.alertbanner

import io.xh.hoist.BaseService
import io.xh.hoist.cache.Cache
import io.xh.hoist.json.JSONParser

import static io.xh.hoist.util.DateTimeUtils.MINUTES
import static java.lang.System.currentTimeMillis

/**
 * Provide support for application alert banners.
 *
 * This class uses a single json blob for its underlying state.
 * The published alert state will be updated when updated by admin, and also
 * updated on a timer in order to catch banner expiry, and any changes to DB.
 */
class AlertBannerService extends BaseService {

    def configService,
        jsonBlobService

    private final static String blobType = 'xhAlertBanner';
    private final static String blobName = 'xhAlertBanner';
    private final static String blobOwner = 'xhAlertBannerService';

    private final emptyAlert = [active: false]
    private Cache<String, Map> cache

    void init() {
        cache = new Cache(name: 'cachedBanner', svc: this, expireTime: 2 * MINUTES, replicate: true)
        super.init()
    }

    /**
     * Main public entry point for clients.
     */
    Map getAlertBanner() {
        return cache.getOrCreate('value') {
            def conf = configService.getMap('xhAlertBannerConfig', [:])
            if (conf.enabled) {
                def spec = getAlertSpec()
                if (spec.active && (!spec.expires || spec.expires > currentTimeMillis())) {
                    return spec
                }
            }
            return emptyAlert
        }
    }

    //--------------------
    // Admin entry points
    //--------------------
    Map getAlertSpec() {
        def svc = jsonBlobService,
            blob = svc.list(blobType, blobOwner).find { it.name == blobName }

        blob ? JSONParser.parseObject(blob.value) : emptyAlert
    }

    void setAlertSpec(Map value) {
        def svc = jsonBlobService,
            blob = svc.list(blobType, blobOwner).find { it.name == blobName }
        if (blob) {
            svc.update(blob.token, [value: value], blobOwner)
        } else {
            svc.create([type: blobType, name: blobName, value: value], blobOwner)
        }
        cache.clear()
    }

    //----------------------------
    // Implementation
    //-----------------------------
    void clearCaches() {
        super.clearCaches()
        cache.clear()
    }
}
