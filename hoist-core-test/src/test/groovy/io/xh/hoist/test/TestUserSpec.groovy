/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.test

import io.xh.hoist.json.JSONParser
import io.xh.hoist.json.JSONSerializer
import spock.lang.Specification

class TestUserSpec extends Specification {

    def 'roles drive the inherited role checks without any RoleService'() {
        when:
        def user = new TestUser('alice', ['HOIST_ADMIN', 'EDITOR'])

        then:
        user.roles == ['HOIST_ADMIN', 'EDITOR'] as Set
        user.hasRole('EDITOR')
        !user.hasRole('OTHER')
        user.hasAnyRole('OTHER', 'EDITOR')
        user.hasAllRoles('EDITOR', 'HOIST_ADMIN')
        !user.hasAllRoles('EDITOR', 'OTHER')
        user.isHoistAdmin
        !user.canImpersonate
    }

    def 'defaults'() {
        when:
        def user = new TestUser('bob')

        then:
        user.roles.isEmpty()
        user.active
        user.email == 'bob@example.com'
        user.displayName == 'bob'
        user.toString() == 'bob'
    }

    def 'explicit display name and roles can be set after construction'() {
        when:
        def user = new TestUser('bob')
        user.displayName = 'Bob Smith'
        user.roles = ['X'] as Set

        then:
        user.displayName == 'Bob Smith'
        user.hasRole('X')
    }

    def 'equality is by username'() {
        expect:
        new TestUser('a', ['X']) == new TestUser('a')
        new TestUser('a') != new TestUser('b')
        new TestUser('a').hashCode() == new TestUser('a').hashCode()
    }

    def 'serializes via formatForJSON'() {
        when:
        def json = JSONParser.parseObject(JSONSerializer.serialize(new TestUser('alice')))

        then:
        json == [username: 'alice', email: 'alice@example.com', displayName: 'alice', active: true]
    }
}
