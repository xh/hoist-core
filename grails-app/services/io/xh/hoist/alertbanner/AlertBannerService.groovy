/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.alertbanner


import io.xh.hoist.cache.Cache
import io.xh.hoist.BaseService
import io.xh.hoist.config.ConfigService
import io.xh.hoist.json.JSONParser
import io.xh.hoist.jsonblob.JsonBlobService
import io.xh.hoist.util.Utils

import static io.xh.hoist.util.DateTimeUtils.MINUTES
import static java.lang.System.currentTimeMillis
import static io.xh.hoist.util.Utils.getAppEnvironment

/**
 * Provide support for application alert banners.
 *
 * This class uses a single {@link io.xh.hoist.jsonblob.JsonBlob} to persist its state.
 * The published alert state is updated via the Hoist Admin console and is regularly refreshed
 * on a timer to catch banner expiry.
 */
class AlertBannerService extends BaseService {

    ConfigService configService
    JsonBlobService jsonBlobService

    private final static String blobName = Utils.isProduction ? 'xhAlertBanner' : "xhAlertBanner_$appEnvironment";
    private final static String blobType = 'xhAlertBanner';
    private final static String blobOwner = 'xhAlertBannerService';

    private final static String presetsBlobName = 'xhAlertBannerPresets';

    private final emptyAlert = [active: false]
    private Cache<String, Map> cache

    void init() {
        cache = new Cache(name: 'xhCachedBanner', svc: this, expireTime: 2 * MINUTES, replicate: true)
        super.init()
    }

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

    List getAlertPresets() {
        def svc = jsonBlobService,
            presetsBlob = svc.list(blobType, blobOwner).find { it.name == presetsBlobName }

        presetsBlob ? JSONParser.parseArray(presetsBlob.value) : []
    }

    void setAlertPresets(List presets) {
        def svc = jsonBlobService,
            blob = svc.list(blobType, blobOwner).find { it.name == presetsBlobName }
        if (blob) {
            svc.update(blob.token, [value: presets], blobOwner)
        } else {
            svc.create([type: blobType, name: presetsBlobName, value: presets], blobOwner)
        }
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

    void clearCaches() {
        super.clearCaches()
        cache.clear()
    }
}
