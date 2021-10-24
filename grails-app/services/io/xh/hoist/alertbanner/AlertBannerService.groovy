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

    private final emptyAlert = [active: false]
    private Map _currentAlert = emptyAlert

    void init() {
        super.init()
        createTimer(
                runFn: this.&refreshCurrentAlert,
                interval: 2 * MINUTES,
                runImmediatelyAndBlock: true
        )
    }

    /**
     * Main public entry point for clients.  Return current alert.
     */
    Map getCurrentAlert() {
        _currentAlert
    }

    //--------------------
    // Admin entry points
    //--------------------
    Map getAlertSpec() {
        jsonBlobService
                .listSystemBlobs('xhAlertBanner')
                .find { it.name == 'currentAlert' }
                ?.with {JSONParser.parseObject(it.value)}
                ?: emptyAlert
    }

    void setAlertSpec(String value) {
        def blob = jsonBlobService.listSystemBlobs('xhAlertBanner').find { it.name == 'currentAlert' } ?:
                jsonBlobService.createSystemBlob('xhAlertBanner', 'currentAlert', '{}', null, null)

        blob.value = value
        blob.save()
        refreshCurrentAlert()
    }

    //----------------------------
    // Implementation
    //-----------------------------
    private Map readCurrentAlert() {
        def conf = configService.getMap('xhAlertBannerConfig', [:])
        if (conf.enabled) {
            def spec = getAlertSpec()
            if (spec.active && (!spec.expires || spec.expires > currentTimeMillis())) {
                return spec
            }
        }
        return emptyAlert
    }

    private void refreshCurrentAlert() {
        _currentAlert = readCurrentAlert()
        log.debug("Publishing new Alert Banner state: " + _currentAlert.toMapString())
    }

    void clearCaches() {
        super.clearCaches()
        refreshCurrentAlert()
    }
}
