/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.user

import io.xh.hoist.test.HoistSpec
import io.xh.hoist.test.fakes.TestUser

class HoistUserSpec extends HoistSpec {

    static class OtherUser implements HoistUser {
        String username
        String email = 'x@y.com'
        boolean active = true
        Set<String> roles = [] as Set
    }

    def 'validateUsername of #username is #expected'() {
        expect:
        TestUser.validateUsername(username) == expected

        where:
        username    | expected
        null        | false
        ''          | false
        'bob'       | true
        'Bob'       | false
        'bob smith' | false
        ' bob'      | false
        'bob@x.com' | true
        'b.o-b_1'   | true
    }

    def 'role checks reflect the roles held'() {
        given:
        def user = new TestUser('bob', ['A'])

        expect:
        user.hasRole('A')
        !user.hasRole('Z')
        user.hasAnyRole(['A', 'Z'] as String[])
        !user.hasAnyRole([] as String[])
        user.hasAllRoles([] as String[])
        !user.hasAllRoles(['A', 'Z'] as String[])
    }

    def 'built-in role accessors map to the HOIST roles'() {
        expect:
        new TestUser('a', ['HOIST_ADMIN']).isHoistAdmin
        !new TestUser('a', ['HOIST_ADMIN']).isHoistAdminReader
        new TestUser('a', ['HOIST_ADMIN_READER']).isHoistAdminReader
        new TestUser('a', ['HOIST_IMPERSONATOR']).canImpersonate
        !new TestUser('a').canImpersonate
    }

    def 'hasGate checks the configured users, with wildcard'() {
        given:
        testConfigService.set('gate', gateUsers)

        expect:
        new TestUser(username).hasGate('gate') == expected

        where:
        gateUsers        | username | expected
        '*'              | 'alice'  | true
        'bob, carl'      | 'bob'    | true
        'bob, carl'      | 'alice'  | false
        ''               | 'bob'    | false
    }

    def 'users are equal by username across implementations'() {
        given:
        def a = new TestUser('bob')
        def b = new OtherUser(username: 'bob')

        expect:
        a == b
        a.hashCode() == b.hashCode()
        a != new TestUser('alice')
        !a.equals('bob')
        ([a, b] as Set).size() == 1
        a.toString() == 'bob'
    }

    def 'displayName defaults to username and formatForJSON has the core fields'() {
        given:
        def user = new OtherUser(username: 'bob')

        expect:
        user.displayName == 'bob'
        user.formatForJSON() == [username: 'bob', email: 'x@y.com', displayName: 'bob', active: true]
    }
}
