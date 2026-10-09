/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.role.provided

import grails.testing.gorm.DataTest
import grails.testing.services.ServiceUnitTest
import io.xh.hoist.BaseService
import io.xh.hoist.role.BaseRoleService
import io.xh.hoist.test.HoistUnitTest
import io.xh.hoist.test.fakes.TestUser
import spock.lang.Specification

import static io.xh.hoist.role.provided.RoleMember.Type.*

/**
 * Tests {@link DefaultRoleUpdateService} against in-memory {@link Role} and {@link RoleMember}
 * tables, wired to a {@link DefaultRoleService} without directory group support. `admin` is
 * logged in, so `lastUpdatedBy` and `createdBy` record that name.
 */
class DefaultRoleUpdateServiceSpec extends Specification implements ServiceUnitTest<DefaultRoleUpdateService>, DataTest, HoistUnitTest {

    static class NoDirectoryRoleService extends DefaultRoleService {
        boolean getDirectoryGroupsSupported() { false }
    }

    /** A custom RoleService, under which the update service is disabled. */
    static class CustomRoleService extends BaseRoleService {
        Map<String, Set<String>> getAllRoleAssignments() { [:] }
        Set<String> getRolesForUser(String username) { [] as Set }
        Set<String> getUsersForRole(String role) { [] as Set }
    }

    Class[] getDomainClassesToMock() { [Role, RoleMember] }

    DefaultRoleService roleService

    def setup() {
        testConfigService.set('xhRoleModuleConfig', [refreshIntervalSecs: 300])
        roleService = defineService(NoDirectoryRoleService)
        service.roleService = roleService
        loginAs(new TestUser('admin', ['HOIST_ADMIN']))
    }

    //-------------------
    // Create
    //-------------------
    def 'create saves the role with its members, lower-casing users, and records an audit entry'() {
        when:
        def role = service.create(name: 'EDITOR', category: 'App', notes: 'n', users: ['Alice', 'bob'], roles: ['ADMIN'], directoryGroups: ['devs'])

        then:
        role.is(Role.get('EDITOR'))
        role.category == 'App'
        role.lastUpdatedBy == 'admin'
        role.users == ['alice', 'bob']
        role.roles == ['ADMIN']
        role.directoryGroups == ['devs']
        role.members.every { it.createdBy == 'admin' }

        and:
        with(testTrackService.lastTracked) {
            category == 'Audit'
            msg == "Created role: 'EDITOR'"
            data.users == ['alice', 'bob']
            data.roles == ['ADMIN']
        }

        and: 'the role service sees the new assignments'
        roleService.getUsersForRole('EDITOR') == ['alice', 'bob'] as Set
    }

    //-------------------
    // Update
    //-------------------
    // Member removal relies on Hibernate's identity map - `removeFromMembers` is handed instances
    // loaded by a separate query, which the in-memory datastore does not match to the collection
    // (see docs/testing.md). Removals are therefore asserted via the audit entry only.
    def 'update reconciles members and tracks exactly what changed'() {
        given:
        service.create(name: 'EDITOR', category: 'App', users: ['alice', 'bob'], roles: ['ADMIN'], directoryGroups: [])
        testTrackService.clear()

        when:
        def role = service.update(name: 'EDITOR', category: 'Ops', notes: 'changed', users: ['bob', 'Carol'], roles: [], directoryGroups: ['devs'])

        then:
        role.category == 'Ops'
        role.notes == 'changed'
        role.users.containsAll(['bob', 'carol'])
        role.directoryGroups == ['devs']

        and:
        with(testTrackService.lastTracked) {
            msg == "Edited role: 'EDITOR'"
            data.addedUsers == ['carol']
            data.removedUsers == ['alice']
            data.removedRoles == ['ADMIN']
            data.addedDirectoryGroups == ['devs']
        }

        and:
        roleService.getUsersForRole('EDITOR').containsAll(['bob', 'carol'])
    }

    def 'update with unchanged members tracks no additions or removals'() {
        given:
        service.create(name: 'EDITOR', users: ['alice'], roles: [], directoryGroups: [])

        when:
        service.update(name: 'EDITOR', users: ['ALICE'], roles: [], directoryGroups: [])

        then:
        with(testTrackService.lastTracked.data) {
            addedUsers == []
            removedUsers == []
        }
    }

    //-------------------
    // Delete
    //-------------------
    def 'delete removes the role and refreshes assignments'() {
        given:
        service.create(name: 'ADMIN', users: ['alice'], roles: [], directoryGroups: [])
        service.create(name: 'EDITOR', users: ['bob'], roles: ['ADMIN'], directoryGroups: [])
        testTrackService.clear()

        when:
        service.delete('ADMIN')

        then:
        Role.get('ADMIN') == null
        testTrackService.lastTracked.msg == "Deleted role: 'ADMIN'"
        roleService.getUsersForRole('EDITOR') == ['bob'] as Set
    }

    //-------------------
    // Other operations
    //-------------------
    def 'bulkCategoryUpdate recategorizes the given roles and tracks once'() {
        given:
        ['A', 'B', 'C'].each { service.create(name: it, category: 'Old', users: [], roles: [], directoryGroups: []) }
        testTrackService.clear()

        when:
        def updated = service.bulkCategoryUpdate(['A', 'B'], 'New')

        then:
        updated*.name.toSet() == ['A', 'B'] as Set
        Role.get('A').category == 'New'
        Role.get('B').category == 'New'
        Role.get('C').category == 'Old'
        testTrackService.tracked.size() == 1
        testTrackService.lastTracked.data == [roles: ['A', 'B'], category: 'New']
    }

    def 'assignRole adds a user to an existing role, and is a no-op if already held'() {
        given:
        service.create(name: 'EDITOR', users: [], roles: [], directoryGroups: [])
        def bob = new TestUser('bob')

        when:
        service.assignRole(bob, 'EDITOR')

        then:
        Role.get('EDITOR').users == ['bob']
        roleService.getUsersForRole('EDITOR') == ['bob'] as Set

        when: 'the user already holds the role'
        service.assignRole(new TestUser('carol', ['EDITOR']), 'EDITOR')

        then:
        Role.get('EDITOR').users == ['bob']

        when: 'the role does not exist'
        service.assignRole(bob, 'MISSING')

        then:
        noExceptionThrown()
    }

    def 'operations are disabled under a custom RoleService'() {
        given:
        service.roleService = new CustomRoleService()

        expect:
        !service.enabled
        service.defaultRoleService == null

        when:
        service.create(name: 'X', users: [], roles: [], directoryGroups: [])

        then:
        def e = thrown(RuntimeException)
        e.message.contains('not enabled')
    }
}
