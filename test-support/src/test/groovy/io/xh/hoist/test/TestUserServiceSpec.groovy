/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.test

import spock.lang.Specification

class TestUserServiceSpec extends Specification {

    def users = new TestUserService()

    def 'finds and lists added users'() {
        given:
        def inactive = new TestUser('carol')
        inactive.active = false
        users.add(new TestUser('alice'), new TestUser('bob')).add(inactive)

        expect:
        users.find('alice').username == 'alice'
        users.find('nobody') == null
        users.list(false)*.username == ['alice', 'bob', 'carol']
        users.list(true)*.username == ['alice', 'bob']
    }

    def 'adding a user with an existing username replaces it'() {
        when:
        users.add(new TestUser('alice')).add(new TestUser('alice', ['X']))

        then:
        users.list(false).size() == 1
        users.find('alice').hasRole('X')
    }

    def 'clear removes all users'() {
        when:
        users.add(new TestUser('alice')).clear()

        then:
        users.list(false).isEmpty()
    }

    def 'inherits impersonation target filtering'() {
        given:
        def impersonator = new TestUser('imp', ['HOIST_IMPERSONATOR'])
        def admin = new TestUser('admin', ['HOIST_ADMIN'])
        def regular = new TestUser('regular')
        users.add(impersonator, admin, regular)

        expect: 'non-admin impersonators cannot target admins'
        users.impersonationTargetsForUser(impersonator)*.username == ['imp', 'regular']

        and: 'users without the impersonator role have no targets'
        users.impersonationTargetsForUser(regular).isEmpty()
    }
}
