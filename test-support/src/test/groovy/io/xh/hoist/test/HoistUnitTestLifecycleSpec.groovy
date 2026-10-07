/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.test

import io.xh.hoist.test.fakes.*
import spock.lang.FailsWith
import spock.lang.Shared
import spock.lang.Stepwise

/** A base spec with its own fixture, which must run after the Hoist beans are ready. */
abstract class LifecycleBaseSpec extends HoistSpec {
    boolean baseFixtureSawBeans = false

    def setup() {
        baseFixtureSawBeans = applicationContext.containsBean('configService')
        testConfigService.set('fromBaseFixture', 'yes')
    }
}

/**
 * Verifies that config, users and identity do not leak between features - each feature records
 * state that the next one then checks for. The Grails test context itself is shared across the
 * features of a spec, so the same beans are reset rather than replaced.
 */
@Stepwise
class HoistUnitTestLifecycleSpec extends LifecycleBaseSpec {

    @Shared TestConfigService firstConfigService

    def 'first feature sees beans in a base-class fixture, and logs in a user and sets config'() {
        expect: 'beans were registered before the base-class setup() ran'
        baseFixtureSawBeans
        testConfigService.getString('fromBaseFixture') == 'yes'
        identityService.username == null

        when:
        firstConfigService = testConfigService
        loginAs('alice')
        testConfigService.set('leak', 'x')
        testClusterService.primaryInstance = false
        testTrackService.track(msg: 'leak')
        testEmailService.sendEmail(to: 'leak@example.com', text: 'leak')

        then:
        identityService.username == 'alice'
    }

    def 'second feature sees nothing from the first'() {
        expect: 'the same bean, with its state reset'
        testConfigService.is(firstConfigService)
        !testConfigService.hasConfig('leak')
        testUserService.list(false).isEmpty()
        identityService.username == null
        testClusterService.primaryInstance
        testTrackService.tracked.isEmpty()
        testEmailService.sent.isEmpty()
    }

    @FailsWith(org.spockframework.runtime.ConditionNotSatisfiedError)
    def 'a feature that logs in and then fails'() {
        loginAs('bob')

        expect: 'a failure, which is expected - see the next feature'
        identityService.username == 'not bob'
    }

    def 'identity is cleared even when the previous feature failed'() {
        expect:
        identityService.username == null
        !testUserService.find('bob')
    }
}
