/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2021 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.alertbanner

import io.xh.hoist.BaseService
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
    private Map cachedBanner = emptyAlert

    void init() {
        super.init()
        createTimer(
                runFn: this.&refreshCachedBanner,
                interval: 2 * MINUTES,
                runImmediatelyAndBlock: true
        )
    }

    /**
     * Main public entry point for clients.
     */
    Map getAlertBanner() {
        cachedBanner
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
        refreshCachedBanner()
    }

    //----------------------------
    // Implementation
    //-----------------------------
    private Map readFromSpec() {
        def conf = configService.getMap('xhAlertBannerConfig', [:])
        if (conf.enabled) {
            def spec = getAlertSpec()
            if (spec.active && (!spec.expires || spec.expires > currentTimeMillis())) {
                return spec
            }
        }
        return emptyAlert
    }

    private void refreshCachedBanner() {
        cachedBanner = readFromSpec()
        log.debug("Refreshing Alert Banner state: " + cachedBanner.toMapString())
    }

    void clearCaches() {
        super.clearCaches()
        refreshCachedBanner()
    }
}
