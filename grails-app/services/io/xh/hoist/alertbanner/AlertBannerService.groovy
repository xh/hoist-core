/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.alertbanner

import groovy.transform.CompileStatic
import io.xh.hoist.BaseService
import io.xh.hoist.cache.CachedValue
import io.xh.hoist.config.ConfigService
import io.xh.hoist.jsonblob.JsonBlobService
import io.xh.hoist.util.Timer
import io.xh.hoist.util.Utils

import static io.xh.hoist.json.JSONParser.parseArray
import static io.xh.hoist.json.JSONParser.parseObject
import static io.xh.hoist.util.DateTimeUtils.MINUTES
import static io.xh.hoist.util.Utils.getAppEnvironment
import static java.lang.System.currentTimeMillis

/**
 * Provide support for application alert banners.
 *
 * This class uses a single {@link io.xh.hoist.jsonblob.JsonBlob} to persist its state.
 * The published alert state is updated via the Hoist Admin console and is regularly refreshed
 * by EnvironmentService.
 *
 * For this service to be active, `xhAlertBannerConfig` config must be specified as `{enabled:true}`.
 */
@CompileStatic
class AlertBannerService extends BaseService {

    ConfigService configService
    JsonBlobService jsonBlobService

    private final static String blobName = Utils.isProduction ? 'xhAlertBanner' : "xhAlertBanner_$appEnvironment"
    private final static String blobType = 'xhAlertBanner'
    private final static String blobOwner = 'xhAlertBannerService'

    private final static String presetsBlobName = 'xhAlertBannerPresets'

    private final Map emptyAlert = [active: false]
    private CachedValue<Map> _alertBanner = createCachedValue(name: 'alertBanner', replicate: true)
    private Timer timer

    void init() {
        timer = createTimer(
            name: 'readFromSpec',
            runFn: this.&readFromSpec,
            interval: 2 * MINUTES,
            primaryOnly: true
        )
        super.init()
    }

    Map getAlertBanner() {
        _alertBanner.get() ?: emptyAlert  // fallback just-in-case.  Never expect to be empty.
    }

    //--------------------
    // Admin entry points
    //--------------------
    Map getAlertSpec() {
        def svc = jsonBlobService,
            blob = svc.list(blobType, blobOwner).find { it.name == blobName }

        blob ? parseObject(blob.value) : emptyAlert
    }

    void setAlertSpec(Map value) {
        def svc = jsonBlobService,
            blob = svc.list(blobType, blobOwner).find { it.name == blobName }
        if (blob) {
            svc.update(blob.token, [value: value], blobOwner)
        } else {
            svc.create([type: blobType, name: blobName, value: value], blobOwner)
        }
        readFromSpec()
    }

    List getAlertPresets() {
        def svc = jsonBlobService,
            presetsBlob = svc.list(blobType, blobOwner).find { it.name == presetsBlobName }

        presetsBlob ? parseArray(presetsBlob.value) : []
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
    private void readFromSpec() {
        def newSpec = emptyAlert

        if (configService.getMap('xhAlertBannerConfig').enabled) {
            def spec = getAlertSpec(),
                expires = spec.expires as Long

            if (spec.active && (!expires || expires > currentTimeMillis())) {
                newSpec = spec
            }
        }

        _alertBanner.set(newSpec)
    }

    void clearCaches() {
        super.clearCaches()
        timer.forceRun()
    }

    Map getAdminStats() {[
        config: configForAdminStats('xhAlertBannerConfig'),
        alertBanner: _alertBanner.get()
    ]}
}
