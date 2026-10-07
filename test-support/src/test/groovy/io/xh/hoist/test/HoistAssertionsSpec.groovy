/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.test

import grails.web.Action
import io.xh.hoist.security.AccessAll
import io.xh.hoist.security.AccessRequiresRole
import spock.lang.Specification

/** A controller with a mix of secured and unsecured actions. */
class MixedController {
    @Action @AccessAll
    def open() {}

    @Action @AccessRequiresRole('X')
    def restricted() {}

    @Action
    def unsecured() {}

    @Action
    def unsecuredAgain(String id) {}

    def notAnAction() {}
}

@AccessRequiresRole('HOIST_ADMIN')
class SecuredByClassController {
    @Action
    def one() {}
}

class SecuredByInheritanceController extends SecuredByClassController {
    @Action
    def two() {}
}

/** Covers the controller action scan. The exception helpers are one-line delegations. */
class HoistAssertionsSpec extends Specification {

    def 'unsecured actions are those with no access annotation on the method or its class'() {
        expect:
        HoistAssertions.findUnsecuredActions(MixedController) == ['unsecured', 'unsecuredAgain']
        HoistAssertions.findUnsecuredActions(SecuredByClassController).isEmpty()
        HoistAssertions.findUnsecuredActions(SecuredByInheritanceController).isEmpty()
    }

    def 'assertAllActionsSecured names the offenders'() {
        when:
        HoistAssertions.assertAllActionsSecured(MixedController)

        then:
        def e = thrown(AssertionError)
        e.message.contains('MixedController')
        e.message.contains('unsecured, unsecuredAgain')
    }
}
