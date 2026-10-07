/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.test

import grails.web.Action
import io.xh.hoist.admin.ConfigAdminController
import io.xh.hoist.exception.*
import io.xh.hoist.security.AccessAll
import io.xh.hoist.security.AccessRequiresRole
import spock.lang.Specification

/** A fake controller with a mix of secured and unsecured actions. */
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

    @Action
    def two() {}
}

class SecuredByInheritanceController extends SecuredByClassController {
    @Action
    def three() {}
}

class HoistAssertionsSpec extends Specification {

    def 'findUnsecuredActions finds actions with no annotation on method or class'() {
        expect:
        HoistAssertions.findUnsecuredActions(MixedController) == ['unsecured', 'unsecuredAgain']
    }

    def 'class level annotations secure all actions, including via inheritance'() {
        expect:
        HoistAssertions.findUnsecuredActions(SecuredByClassController).isEmpty()
        HoistAssertions.findUnsecuredActions(SecuredByInheritanceController).isEmpty()
    }

    def 'assertAllActionsSecured fails with the names of the offenders'() {
        when:
        HoistAssertions.assertAllActionsSecured(MixedController)

        then:
        def e = thrown(AssertionError)
        e.message.contains('MixedController')
        e.message.contains('unsecured, unsecuredAgain')

        when:
        HoistAssertions.assertAllActionsSecured(SecuredByClassController)

        then:
        noExceptionThrown()
    }

    def 'real hoist admin controller actions are secured'() {
        expect:
        HoistAssertions.assertAllActionsSecured(ConfigAdminController)
    }

    def 'httpStatusFor maps exceptions to status codes'() {
        expect:
        HoistAssertions.httpStatusFor(ex) == status

        where:
        ex                                         | status
        new NotAuthorizedException()               | 403
        new NotFoundException()                    | 404
        new RoutineRuntimeException('expected')    | 400
        new RuntimeException('bug')                | 500
    }

    def 'isRoutine distinguishes expected from unexpected exceptions'() {
        expect:
        HoistAssertions.isRoutine(new RoutineRuntimeException('x'))
        HoistAssertions.isRoutine(new grails.validation.ValidationException('x', new org.springframework.validation.BeanPropertyBindingResult(new Object(), 'o')))
        !HoistAssertions.isRoutine(new NotFoundException())
        !HoistAssertions.isRoutine(new RuntimeException('x'))
    }
}
