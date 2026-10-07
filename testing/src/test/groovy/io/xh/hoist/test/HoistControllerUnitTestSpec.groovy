/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.test

import grails.testing.web.controllers.ControllerUnitTest
import io.xh.hoist.BaseController
import io.xh.hoist.config.ConfigService
import io.xh.hoist.security.AccessAll
import spock.lang.Specification

/** A typical app controller - reads soft config and the current user, and renders JSON. */
@AccessAll
class SampleController extends BaseController {

    ConfigService configService

    def whoami() {
        logInfo('whoami requested')
        renderJSON(username: username, greeting: configService.getString('greeting'))
    }
}

/** HoistUnitTest combines with the standard Grails ControllerUnitTest trait. */
class HoistControllerUnitTestSpec extends Specification implements ControllerUnitTest<SampleController>, HoistUnitTest {

    def 'controller action renders JSON using soft config and the logged-in user'() {
        given:
        testConfigService.set('greeting', 'hello')
        loginAs('alice')

        when:
        controller.whoami()

        then:
        HoistJson.parse(response.text) == [username: 'alice', greeting: 'hello']
    }

    def 'controller actions are all secured'() {
        expect:
        HoistAssertions.findUnsecuredActions(SampleController).isEmpty()
    }
}
