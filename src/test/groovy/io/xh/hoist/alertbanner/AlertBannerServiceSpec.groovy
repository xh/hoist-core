/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.alertbanner

import grails.testing.gorm.DataTest
import grails.testing.services.ServiceUnitTest
import io.xh.hoist.jsonblob.JsonBlob
import io.xh.hoist.jsonblob.JsonBlobConfig
import io.xh.hoist.jsonblob.JsonBlobService
import io.xh.hoist.test.HoistUnitTest
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

import static java.lang.System.currentTimeMillis

/**
 * Tests {@link AlertBannerService} over a {@link JsonBlobService} backed by an in-memory
 * {@link JsonBlob} table.
 */
class AlertBannerServiceSpec extends Specification implements ServiceUnitTest<AlertBannerService>, DataTest, HoistUnitTest {

    static final Map ACTIVE = [active: true, message: 'Maintenance tonight', intent: 'warning']
    static final Map EMPTY = [active: false]

    Class[] getDomainClassesToMock() { [JsonBlob] }

    JsonBlobService blobs

    def setup() {
        testConfigService.registerTypedConfig('xhJsonBlobConfig', JsonBlobConfig)
        testConfigService.registerTypedConfig('xhAlertBannerConfig', AlertBannerConfig)
        blobs = defineService(JsonBlobService)
    }

    def 'with nothing stored there is no alert'() {
        expect:
        service.alertSpec == EMPTY
        service.alertBanner == EMPTY
        service.alertPresets == []
    }

    def 'setting an active spec stores it in a blob and publishes it'() {
        when:
        service.setAlertSpec(ACTIVE)

        then:
        service.alertSpec == ACTIVE
        service.alertBanner == ACTIVE
        blobs.list('xhAlertBanner', 'xhAlertBannerService').size() == 1

        when: 'set again, the same blob is updated'
        service.setAlertSpec(ACTIVE + [message: 'Changed'])

        then:
        service.alertBanner.message == 'Changed'
        blobs.list('xhAlertBanner', 'xhAlertBannerService').size() == 1
    }

    def 'the published banner is empty when the spec is #desc'() {
        when:
        service.setAlertSpec(spec)

        then:
        service.alertSpec == spec
        service.alertBanner == EMPTY

        where:
        desc       | spec
        'inactive' | ACTIVE + [active: false]
        'expired'  | ACTIVE + [expires: currentTimeMillis() - 1000]
    }

    def 'a spec with a future expiry is published'() {
        when:
        service.setAlertSpec(ACTIVE + [expires: currentTimeMillis() + 60_000])

        then:
        service.alertBanner.active
    }

    def 'nothing is published when the feature is disabled by config'() {
        given:
        testConfigService.set('xhAlertBannerConfig', [enabled: false])

        when:
        service.setAlertSpec(ACTIVE)

        then:
        service.alertSpec == ACTIVE
        service.alertBanner == EMPTY
    }

    def 'presets are stored in their own blob'() {
        when:
        service.setAlertPresets([[message: 'A'], [message: 'B']])

        then:
        service.alertPresets == [[message: 'A'], [message: 'B']]

        when:
        service.setAlertPresets([[message: 'C']])

        then:
        service.alertPresets == [[message: 'C']]
        blobs.list('xhAlertBanner', 'xhAlertBannerService')*.name == ['xhAlertBannerPresets']
    }

    def 'clearCaches re-reads a spec written behind the service\'s back'() {
        given:
        service.init()
        service.setAlertSpec(ACTIVE)
        def blob = blobs.list('xhAlertBanner', 'xhAlertBannerService')[0]
        blobs.update(blob.token, [value: ACTIVE + [active: false]], 'xhAlertBannerService')

        expect:
        service.alertBanner == ACTIVE

        when:
        service.clearCaches()

        then:
        new PollingConditions(timeout: 5).eventually {
            assert service.alertBanner == EMPTY
        }
    }
}
