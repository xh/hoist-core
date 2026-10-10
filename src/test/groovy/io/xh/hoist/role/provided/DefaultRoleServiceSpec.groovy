/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.role.provided

import grails.testing.gorm.DataTest
import grails.testing.services.ServiceUnitTest
import io.xh.hoist.test.HoistUnitTest
import io.xh.hoist.util.ErrorOr
import spock.lang.Specification

import java.util.concurrent.CountDownLatch

import static io.xh.hoist.role.provided.RoleMember.Type.*
import static java.util.concurrent.TimeUnit.SECONDS

/**
 * Tests role assignment resolution in {@link DefaultRoleService} against in-memory {@link Role}
 * and {@link RoleMember} tables. Directory group lookups are supplied by the subclass under test,
 * so no LDAP or Entra ID service is involved.
 */
class DefaultRoleServiceSpec extends Specification implements ServiceUnitTest<DefaultRoleServiceSpec.FixedDirectoryRoleService>, DataTest, HoistUnitTest {

    /** Resolves directory groups from a fixed map, standing in for LDAP or Entra ID. */
    static class FixedDirectoryRoleService extends DefaultRoleService {
        Map<String, Set<String>> groupMembers = [:]
        boolean directoryGroups = true
        int directoryLookups = 0

        boolean getDirectoryGroupsSupported() { directoryGroups }

        Map<String, ErrorOr<Set<String>>> loadUsersForDirectoryGroups(Set<String> groups, boolean strictMode) {
            directoryLookups++
            groups.collectEntries { [it, groupMembers.containsKey(it) ? ErrorOr.of(groupMembers[it]) : ErrorOr.error("no such group $it")] }
        }
    }

    Class[] getDomainClassesToMock() { [Role, RoleMember] }

    def setup() {
        testConfigService.set('xhRoleModuleConfig', [refreshIntervalSecs: 300, directoryGroupProvider: 'auto'])
    }

    //-------------------
    // Assignment resolution
    //-------------------
    def 'direct user members are assigned, lower-cased'() {
        given:
        role('READER', users: ['Alice', 'bob'])

        when:
        service.refreshRoleAssignments()

        then:
        service.allRoleAssignments == [READER: ['alice', 'bob'] as Set]
        service.getUsersForRole('READER') == ['alice', 'bob'] as Set
        service.getUsersForRole('NOPE').isEmpty()
        service.getRolesForUser('ALICE') == ['READER'] as Set
        service.getRolesForUser('carol').isEmpty()
    }

    def 'users holding a member role inherit the parent role, transitively'() {
        given:
        role('ADMIN', users: ['alice'])
        role('EDITOR', users: ['bob'], roles: ['ADMIN'])
        role('READER', users: ['carol'], roles: ['EDITOR'])

        when:
        service.refreshRoleAssignments()

        then:
        service.getUsersForRole('ADMIN') == ['alice'] as Set
        service.getUsersForRole('EDITOR') == ['alice', 'bob'] as Set
        service.getUsersForRole('READER') == ['alice', 'bob', 'carol'] as Set
        service.getRolesForUser('alice') == ['ADMIN', 'EDITOR', 'READER'] as Set
        service.getRolesForUser('carol') == ['READER'] as Set
    }

    def 'cyclic and dangling role references are tolerated'() {
        given:
        role('A', users: ['alice'], roles: ['B', 'MISSING'])
        role('B', users: ['bob'], roles: ['A'])

        when:
        service.refreshRoleAssignments()

        then:
        service.getUsersForRole('A') == ['alice', 'bob'] as Set
        service.getUsersForRole('B') == ['alice', 'bob'] as Set
    }

    def 'directory group members are resolved and merged with direct users'() {
        given:
        service.groupMembers = [devs: ['Dave', 'erin'] as Set]
        role('DEV', users: ['alice'], directoryGroups: ['devs'])
        role('LEAD', roles: ['DEV'])

        when:
        service.refreshRoleAssignments()

        then:
        service.getUsersForRole('DEV') == ['alice', 'dave', 'erin'] as Set
        service.getUsersForRole('LEAD') == ['alice', 'dave', 'erin'] as Set
        service.directoryLookups == 1
    }

    def 'a group that fails to resolve contributes no users and does not block the others'() {
        given:
        service.groupMembers = [devs: ['dave'] as Set]
        role('DEV', directoryGroups: ['devs', 'ghosts'])

        when:
        service.refreshRoleAssignments()

        then:
        service.getUsersForRole('DEV') == ['dave'] as Set
    }

    def 'directory groups are ignored when the implementation does not support them'() {
        given:
        service.directoryGroups = false
        service.groupMembers = [devs: ['dave'] as Set]
        role('DEV', users: ['alice'], directoryGroups: ['devs'])

        when:
        service.refreshRoleAssignments()

        then:
        service.getUsersForRole('DEV') == ['alice'] as Set
        service.directoryLookups == 0
    }

    def 'per-user lookups are cached and invalidated on refresh'() {
        given:
        role('READER', users: ['alice'])
        refreshAndAwaitInvalidation()

        expect:
        service.getRolesForUser('alice') == ['READER'] as Set
        service.getRolesForUser('alice').is(service.getRolesForUser('alice'))

        when:
        role('EDITOR', users: ['alice'])
        refreshAndAwaitInvalidation()

        then:
        service.getRolesForUser('alice') == ['READER', 'EDITOR'] as Set
    }

    //-------------------
    // Bootstrap and assignment
    //-------------------
    def 'ensureRequiredRolesCreated creates missing roles with members and leaves existing ones alone'() {
        given:
        def updateService = defineService(DefaultRoleUpdateService)
        updateService.roleService = service
        role('EXISTING', users: ['alice'])

        when:
        updateService.ensureRequiredRolesCreated([
            new RoleSpec(name: 'EXISTING', users: ['bob']),
            new RoleSpec(name: 'NEW', category: 'App', notes: 'n', users: ['Carol'], roles: ['EXISTING'], directoryGroups: ['devs']),
        ])
        service.refreshRoleAssignments()

        then:
        service.getUsersForRole('EXISTING') == ['alice'] as Set
        with(Role.get('NEW')) {
            category == 'App'
            notes == 'n'
            lastUpdatedBy == 'defaultRoleService'
            users == ['carol']
            roles == ['EXISTING']
            directoryGroups == ['devs']
        }
    }

    //-------------------
    // Helpers
    //-------------------
    // The per-user cache is cleared by an async change handler - wait for it, so a lookup cannot
    // race the clear. Handlers run in the order added, so ours runs after the service's.
    private void refreshAndAwaitInvalidation() {
        def handled = new CountDownLatch(1)
        service._allRoleAssignments.addChangeHandler { handled.countDown() }
        service.refreshRoleAssignments()
        assert handled.await(5, SECONDS)
    }

    private Role role(Map members = [:], String name) {
        def role = new Role(name: name, lastUpdatedBy: 'test')
        members.users?.each { role.addToMembers(type: USER, name: it, createdBy: 'test') }
        members.roles?.each { role.addToMembers(type: ROLE, name: it, createdBy: 'test') }
        members.directoryGroups?.each { role.addToMembers(type: DIRECTORY_GROUP, name: it, createdBy: 'test') }
        role.save(flush: true, failOnError: true)
    }
}
