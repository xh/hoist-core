/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.role.provided

import grails.testing.gorm.DataTest
import grails.testing.services.ServiceUnitTest
import io.xh.hoist.json.JSONParser
import io.xh.hoist.json.JSONSerializer
import io.xh.hoist.test.HoistUnitTest
import io.xh.hoist.util.ErrorOr
import spock.lang.Specification

import static io.xh.hoist.role.provided.RoleMember.Type.*

/**
 * Tests the role listing built by {@link DefaultRoleAdminService} for the Admin Console, against
 * in-memory {@link Role} and {@link RoleMember} tables. Results are read back through JSON, as the
 * client receives them. Directory group lookups are supplied by a stub role service.
 */
class DefaultRoleAdminServiceSpec extends Specification implements ServiceUnitTest<DefaultRoleAdminService>, DataTest, HoistUnitTest {

    /** Resolves directory groups from a fixed map, standing in for LDAP or Entra ID. */
    static class FixedDirectoryRoleService extends DefaultRoleService {
        Map<String, Set<String>> groupMembers = [:]
        boolean directoryGroups = true
        boolean userAssignment = true

        boolean getDirectoryGroupsSupported() { directoryGroups }
        boolean getUserAssignmentSupported() { userAssignment }

        Map<String, ErrorOr<Set<String>>> loadUsersForDirectoryGroups(Set<String> groups, boolean strictMode) {
            groups.collectEntries { [it, groupMembers.containsKey(it) ? ErrorOr.of(groupMembers[it]) : ErrorOr.error("no such group $it")] }
        }
    }

    Class[] getDomainClassesToMock() { [Role, RoleMember] }

    FixedDirectoryRoleService roleService

    def setup() {
        testConfigService.set('xhRoleModuleConfig', [refreshIntervalSecs: 300, directoryGroupProvider: 'auto'])
        roleService = defineService(FixedDirectoryRoleService)
        service.roleService = roleService
    }

    def 'a user assigned directly and via a group has a source for each'() {
        given:
        roleService.groupMembers = [devs: ['alice', 'Dave'] as Set]
        role('DEV', users: ['Alice'], directoryGroups: ['devs'])

        expect:
        listRoles().DEV.effectiveUsers as Set == [
            [name: 'alice', sources: [[role: 'DEV'], [role: 'DEV', directoryGroup: 'devs']]],
            [name: 'dave', sources: [[role: 'DEV', directoryGroup: 'devs']]]
        ] as Set
    }

    def 'member roles are resolved transitively, in both directions, with their sources'() {
        given:
        role('ADMIN', users: ['alice'])
        role('EDITOR', users: ['bob'], roles: ['ADMIN'])
        role('READER', users: ['carol'], roles: ['EDITOR'])

        when:
        def roles = listRoles()

        then: 'effectiveRoles are the roles whose members also hold this role'
        roles.ADMIN.effectiveRoles == []
        roles.EDITOR.effectiveRoles == [[name: 'ADMIN', sourceRoles: ['EDITOR']]]
        roles.READER.effectiveRoles == [
            [name: 'EDITOR', sourceRoles: ['READER']],
            [name: 'ADMIN', sourceRoles: ['EDITOR']]
        ]

        and: 'inheritedRoles are the roles that this role\'s members also hold'
        roles.ADMIN.inheritedRoles == [
            [name: 'EDITOR', sourceRoles: ['ADMIN']],
            [name: 'READER', sourceRoles: ['EDITOR']]
        ]
        roles.EDITOR.inheritedRoles == [[name: 'READER', sourceRoles: ['EDITOR']]]
        roles.READER.inheritedRoles == []

        and:
        roles.READER.effectiveUsers == [
            [name: 'carol', sources: [[role: 'READER']]],
            [name: 'bob', sources: [[role: 'EDITOR']]],
            [name: 'alice', sources: [[role: 'ADMIN']]]
        ]
        roles.ADMIN.effectiveUsers == [[name: 'alice', sources: [[role: 'ADMIN']]]]
    }

    def 'a role reached by more than one path is listed once, with every source'() {
        given:
        role('BASE', users: ['alice'])
        role('LEFT', roles: ['BASE'])
        role('RIGHT', roles: ['BASE'])
        role('TOP', users: ['bob'], roles: ['LEFT', 'RIGHT'])

        when:
        def roles = listRoles()

        then:
        roles.TOP.effectiveRoles.collect(this.&sortSources) as Set == [
            [name: 'LEFT', sourceRoles: ['TOP']],
            [name: 'RIGHT', sourceRoles: ['TOP']],
            [name: 'BASE', sourceRoles: ['LEFT', 'RIGHT']]
        ] as Set
        roles.TOP.effectiveUsers as Set == [
            [name: 'bob', sources: [[role: 'TOP']]],
            [name: 'alice', sources: [[role: 'BASE']]]
        ] as Set

        and:
        roles.BASE.inheritedRoles.collect(this.&sortSources) as Set == [
            [name: 'LEFT', sourceRoles: ['BASE']],
            [name: 'RIGHT', sourceRoles: ['BASE']],
            [name: 'TOP', sourceRoles: ['LEFT', 'RIGHT']]
        ] as Set
    }

    def 'cyclic role references terminate'() {
        given:
        role('A', users: ['alice'], roles: ['B'])
        role('B', users: ['bob'], roles: ['A'])

        when:
        def roles = listRoles()

        then:
        roles.A.effectiveRoles*.name as Set == ['A', 'B'] as Set
        roles.A.inheritedRoles*.name as Set == ['A', 'B'] as Set
        roles.A.effectiveUsers*.name as Set == ['alice', 'bob'] as Set
        roles.B.effectiveUsers*.name as Set == ['alice', 'bob'] as Set
    }

    def 'a reference to a role that does not exist is skipped'() {
        given:
        role('ADMIN', users: ['alice'])
        role('EDITOR', users: ['bob'], roles: ['MISSING', 'ADMIN'])

        when:
        def editor = listRoles().EDITOR

        then:
        editor.members*.name as Set == ['bob', 'MISSING', 'ADMIN'] as Set
        editor.effectiveRoles == [[name: 'ADMIN', sourceRoles: ['EDITOR']]]
        editor.effectiveUsers*.name as Set == ['alice', 'bob'] as Set
    }

    def 'directory group users and errors are attributed to the roles that include the group'() {
        given:
        roleService.groupMembers = [devs: ['Dave', 'erin'] as Set]
        role('DEV', users: ['alice'], directoryGroups: ['devs', 'ghosts'])
        role('LEAD', roles: ['DEV'])
        role('OTHER', users: ['zed'])

        when:
        def roles = listRoles()

        then:
        roles.LEAD.effectiveDirectoryGroups as Set == [
            [name: 'devs', sourceRoles: ['DEV']],
            [name: 'ghosts', sourceRoles: ['DEV']]
        ] as Set
        roles.LEAD.effectiveUsers as Set == [
            [name: 'alice', sources: [[role: 'DEV']]],
            [name: 'dave', sources: [[role: 'DEV', directoryGroup: 'devs']]],
            [name: 'erin', sources: [[role: 'DEV', directoryGroup: 'devs']]]
        ] as Set
        roles.LEAD.errors == [directoryGroups: [ghosts: 'no such group ghosts']]
        roles.OTHER.errors == [directoryGroups: [:]]
    }

    def 'users and groups are omitted from effective users when not supported'() {
        given:
        roleService.userAssignment = userAssignment
        roleService.directoryGroups = directoryGroups
        roleService.groupMembers = [devs: ['dave'] as Set]
        role('DEV', users: ['alice'], directoryGroups: ['devs'])

        expect:
        listRoles().DEV.effectiveUsers*.name as Set == expected as Set

        where:
        userAssignment | directoryGroups || expected
        true           | true            || ['alice', 'dave']
        true           | false           || ['alice']
        false          | true            || ['dave']
    }

    //-------------------
    // Helpers
    //-------------------
    private Map<String, Map> listRoles() {
        List<Map> roles = JSONParser.parseArray(JSONSerializer.serialize(service.list()))
        roles.collectEntries { [it.name, it] }
    }

    private static Map sortSources(Map member) {
        member + [sourceRoles: member.sourceRoles.toSorted()]
    }

    private Role role(Map props = [:], String name) {
        def role = new Role(name: name, category: props.category, notes: props.notes, lastUpdatedBy: 'test')
        props.users?.each { role.addToMembers(type: USER, name: it, createdBy: 'test') }
        props.roles?.each { role.addToMembers(type: ROLE, name: it, createdBy: 'test') }
        props.directoryGroups?.each { role.addToMembers(type: DIRECTORY_GROUP, name: it, createdBy: 'test') }
        role.save(flush: true, failOnError: true)
    }
}
