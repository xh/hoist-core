/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.test

import io.xh.hoist.test.fakes.*
import io.xh.hoist.util.Utils

/** An app-specific config service, e.g. with extra helper methods. */
class CustomConfigService extends TestConfigService {
    String getGreeting() { getString('greeting', 'default greeting') }
}

/** Beans the spec defines in doWithSpring take precedence over the Hoist defaults. */
class HoistUnitTestOverrideSpec extends HoistSpec {

    Closure doWithSpring() {{ ->
        configService(CustomConfigService)
    }}

    def 'a configService from doWithSpring replaces the default'() {
        expect:
        testConfigService instanceof CustomConfigService
        Utils.configService.is(testConfigService)
        ((CustomConfigService) testConfigService).greeting == 'default greeting'

        and: 'the other defaults are still registered'
        identityService != null
        testUserService != null
    }

    def 'an overriding TestConfigService is still reset between features'() {
        when:
        testConfigService.set('greeting', 'hello')

        then:
        ((CustomConfigService) testConfigService).greeting == 'hello'
    }

    def 'and is empty again in the next feature'() {
        expect:
        ((CustomConfigService) testConfigService).greeting == 'default greeting'
    }
}
