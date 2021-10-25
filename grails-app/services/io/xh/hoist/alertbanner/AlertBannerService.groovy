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

    private final static String BLOB_ID = 'xhAlertBanner';

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
        jsonBlobService
                .listSystemBlobs(BLOB_ID)
                .find { it.name == BLOB_ID }
                ?.with {JSONParser.parseObject(it.value)}
                ?: emptyAlert
    }

    void setAlertSpec(String value) {
        def blob = jsonBlobService.listSystemBlobs(BLOB_ID).find { it.name == BLOB_ID } ?:
                jsonBlobService.createSystemBlob(BLOB_ID, BLOB_ID, '{}', null, null)

        blob.value = value
        blob.save()
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
