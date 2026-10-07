/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.user

import grails.testing.services.ServiceUnitTest
import grails.testing.web.GrailsWebUnitTest
import io.xh.hoist.security.BaseAuthenticationService
import io.xh.hoist.test.HoistUnitTest
import io.xh.hoist.test.fakes.TestUser
import spock.lang.Specification

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse

/**
 * Tests impersonation, login and logout in {@link IdentityService} within a mock web request,
 * supplied by `GrailsWebUnitTest`. Three users are known: `admin` (HOIST_ADMIN and
 * HOIST_IMPERSONATOR), `imp` (HOIST_IMPERSONATOR only) and `bob`.
 */
class IdentityServiceSpec extends Specification implements ServiceUnitTest<IdentityService>, GrailsWebUnitTest, HoistUnitTest {

    static class StubAuthenticationService extends BaseAuthenticationService {
        boolean loginResult = false
        boolean logoutResult = false
        List loginCalls = []

        protected boolean completeAuthentication(HttpServletRequest request, HttpServletResponse response) { true }
        boolean login(HttpServletRequest request, String username, String password) { loginCalls << [username, password]; loginResult }
        boolean logout() { logoutResult }
    }

    StubAuthenticationService authService = new StubAuthenticationService()

    def setup() {
        service.authenticationService = authService
        testConfigService.set('xhEnableImpersonation', true)
        testUserService.add(
            new TestUser('admin', ['HOIST_ADMIN', 'HOIST_IMPERSONATOR']),
            new TestUser('imp', ['HOIST_IMPERSONATOR']),
            new TestUser('bob')
        )
    }

    //-------------------
    // Impersonation
    //-------------------
    def 'an impersonator takes on the target user while keeping their authenticated identity'() {
        given:
        loginAs('imp')

        when:
        def target = service.impersonate('bob')

        then:
        target.username == 'bob'
        service.username == 'bob'
        service.user.username == 'bob'
        service.authUsername == 'imp'
        service.authUser.username == 'imp'
        service.impersonating

        and: 'the session carries the identity for later requests'
        (session.getAttribute('xhIdentity') as HoistIdentity).username == 'bob'

        and:
        with(testTrackService.lastTracked) {
            category == 'Impersonate'
            msg == 'Started impersonation'
            severity == 'WARN'
            data == [target: 'bob']
        }
    }

    def 'impersonation is refused when #reason'() {
        given:
        testConfigService.set('xhEnableImpersonation', enabled)
        loginAs(actor)

        when:
        service.impersonate(target)

        then:
        def e = thrown(RuntimeException)
        e.message.contains(message)
        !service.impersonating
        testTrackService.tracked.isEmpty()

        where:
        reason                               | enabled | actor   | target    | message
        'disabled by config'                 | false   | 'admin' | 'bob'     | 'disabled'
        'the user lacks the impersonator role' | true  | 'bob'   | 'admin'   | 'not authorized for impersonation'
        'the target does not exist'          | true    | 'admin' | 'nobody'  | 'not found'
        'a non-admin targets an admin'       | true    | 'imp'   | 'admin'   | 'not authorized to impersonate'
    }

    def 'impersonation requires a request'() {
        given:
        loginAs('admin')
        webRequest = null
        org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes()

        when:
        service.impersonate('bob')

        then:
        def e = thrown(RuntimeException)
        e.message.contains('outside the context of a request')
    }

    def 'switching targets ends the previous impersonation first'() {
        given:
        loginAs('admin')
        service.impersonate('bob')

        when:
        service.impersonate('imp')

        then:
        service.username == 'imp'
        service.authUsername == 'admin'
        testTrackService.tracked*.msg == ['Started impersonation', 'Stopped impersonation', 'Started impersonation']
        testTrackService.tracked*.data*.target == ['bob', 'bob', 'imp']
    }

    def 'endImpersonate restores the authenticated user, and is a no-op otherwise'() {
        given:
        loginAs('admin')
        service.impersonate('bob')

        when:
        service.endImpersonate()

        then:
        service.username == 'admin'
        !service.impersonating
        testTrackService.lastTracked.msg == 'Stopped impersonation'
        testTrackService.lastTracked.data == [target: 'bob']

        when:
        testTrackService.clear()
        service.endImpersonate()

        then:
        testTrackService.tracked.isEmpty()
    }

    //-------------------
    // Client config
    //-------------------
    def 'clientConfig describes the user, or both users when impersonating, or nothing when logged out'() {
        expect:
        service.clientConfig == null

        when:
        loginAs('admin')

        then:
        service.clientConfig.keySet() == ['user', 'roles'] as Set
        service.clientConfig.user.username == 'admin'
        service.clientConfig.roles == ['HOIST_ADMIN', 'HOIST_IMPERSONATOR'] as Set

        when:
        service.impersonate('bob')

        then:
        with(service.clientConfig) {
            keySet() == ['apparentUser', 'apparentUserRoles', 'authUser', 'authUserRoles'] as Set
            apparentUser.username == 'bob'
            apparentUserRoles.isEmpty()
            authUser.username == 'admin'
        }
    }

    //-------------------
    // Login and logout
    //-------------------
    def 'login delegates to the authentication service with the current request'() {
        given:
        authService.loginResult = true

        expect:
        service.login('bob', 'pw')
        authService.loginCalls == [['bob', 'pw']]
    }

    def 'logout clears identity only when the authentication service confirms it'() {
        given:
        loginAs('bob')

        when: 'declined, e.g. under SSO'
        def declined = service.logout()

        then:
        !declined
        service.username == 'bob'

        when:
        authService.logoutResult = true
        def accepted = service.logout()

        then:
        accepted
        service.username == null
    }

    def 'an authenticated user is recorded on the session and re-installed from it on a later request'() {
        when:
        service.noteUserAuthenticated(request, testUserService.find('bob'))

        then:
        service.username == 'bob'
        service.authUsername == 'bob'

        when: 'a new thread of execution starts with no identity'
        service.installThreadIdentity(null)

        then:
        service.username == null

        when:
        service.installIdentityFromRequest(request)

        then:
        service.username == 'bob'
    }
}
