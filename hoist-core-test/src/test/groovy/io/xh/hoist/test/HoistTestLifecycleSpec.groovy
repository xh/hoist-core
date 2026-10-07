/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.test

import spock.lang.FailsWith
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Stepwise

/**
 * Verifies that contexts and identity do not leak between features - each feature records state
 * that the next one then checks for. Also exercises {@link HoistTest} on a plain Specification,
 * alongside the spec's own fixture methods.
 */
@HoistTest
@Stepwise
class HoistTestLifecycleSpec extends Specification {

    @Shared HoistTestContext first
    @Shared HoistTestContext second
    boolean fixtureRan = false

    def setup() {
        fixtureRan = HoistTestContext.current != null
    }

    def 'first feature has a context, and logs in a user and sets config'() {
        expect: 'context installed before the spec fixture runs'
        fixtureRan
        HoistTestContext.current.identityService.username == null

        when:
        first = HoistTestContext.current
        first.loginAs('alice')
        first.configService.set('leak', 'x')

        then:
        first.identityService.username == 'alice'
    }

    def 'first context is closed, and second feature sees nothing from the first'() {
        given:
        second = HoistTestContext.current

        expect:
        !first.applicationContext.active
        !second.is(first)
        second.identityService.username == null
        !second.configService.hasConfig('leak')
        second.userService.list(false).isEmpty()
    }

    @FailsWith(org.spockframework.runtime.ConditionNotSatisfiedError)
    def 'a feature that logs in and then fails'() {
        HoistTestContext.current.loginAs('bob')

        expect: 'a failure, which is expected - see the next feature'
        HoistTestContext.current.identityService.username == 'not bob'
    }

    def 'context is closed even when the previous feature failed'() {
        expect:
        HoistTestContext.current.identityService.username == null
        !HoistTestContext.current.userService.find('bob')
    }
}
