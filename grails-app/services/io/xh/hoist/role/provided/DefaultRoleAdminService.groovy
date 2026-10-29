/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.role.provided

import grails.gorm.transactions.ReadOnly
import io.xh.hoist.BaseService
import io.xh.hoist.json.JSONFormat

import static java.util.Collections.emptyMap

/**
 * Service to support built-in, database-backed Role management and its associated Admin Console UI.
 *
 * Requires applications to have opted-in to Hoist's {@link DefaultRoleService} for role management.
 * See that class for additional documentation and details.
 *
 * This class is intended to be used by Hoist and its default role admin UI - apps should
 * rarely (if ever) need to interact with it directly.
 */
class DefaultRoleAdminService extends BaseService {
    def roleService

    boolean getEnabled() {
        roleService instanceof DefaultRoleService
    }

    DefaultRoleService getDefaultRoleService() {
        roleService instanceof DefaultRoleService ? roleService as DefaultRoleService : null
    }

    /**
     * List all Roles with all available membership information and metadata, for display in the
     * Hoist Admin Console. Includes fully resolved effective users, directory groups, and roles.
     */
    @ReadOnly
    List<Map> list() {
        ensureEnabled()

        List<Role> roles = Role.list()
        Map<String, Set<String>> usersForGroups = emptyMap()
        Map<String, String> errorsForGroups = emptyMap()

        if (defaultRoleService.directoryGroupsSupported) {
            Set<String> groups = roles.collectMany(new HashSet()) { it.directoryGroups }
            if (groups) {
                try {
                    def groupsLookup = defaultRoleService.loadUsersForDirectoryGroups(groups, true)
                    usersForGroups = groupsLookup
                        .findAll { it.value.success }
                        .collectEntries { [it.key, it.value.value] }
                    errorsForGroups = groupsLookup
                        .findAll { !it.value.success }
                        .collectEntries { [it.key, it.value.error] }
                } catch (Throwable e) {
                    def errMsg = 'Error resolving Directory Groups'
                    logError(errMsg, e)
                    errorsForGroups = groups.collectEntries {[it, errMsg] }
                }
            }
        }

        def index = new RoleIndex(roles)
        roles.collect {role ->
            def inheritedRoles = getInheritedRoles(role.name, index),
                effectiveRoles = getEffectiveRoles(role.name, index),
                effectiveGroups = getEffectiveMembers(role.name, effectiveRoles, index.directoryGroups),
                effectiveAssignedUsers = getEffectiveMembers(role.name, effectiveRoles, index.users),
                effectiveUsers = getEffectiveUsers(effectiveAssignedUsers, effectiveGroups, usersForGroups),
                effectiveGroupNames = effectiveGroups*.name.toSet()
            [
                name                    : role.name,
                category                : role.category,
                notes                   : role.notes,
                members                 : role.members,
                lastUpdated             : role.lastUpdated,
                lastUpdatedBy           : role.lastUpdatedBy,
                inheritedRoles          : inheritedRoles,
                effectiveRoles          : effectiveRoles,
                effectiveDirectoryGroups: effectiveGroups,
                effectiveUsers          : effectiveUsers,
                errors                  : [
                    directoryGroups: errorsForGroups.findAll {it.key in effectiveGroupNames}
                ]
            ]
        }
    }

    Map getClientConfig() { [
        userAssignmentSupported: defaultRoleService.userAssignmentSupported,
        directoryGroupsSupported: defaultRoleService.directoryGroupsSupported,
        directoryGroupsDescription: defaultRoleService.directoryGroupsDescription
    ]}


    //------------------------
    // Implementation
    //------------------------
    private List<EffectiveMember> getInheritedRoles(String roleName, RoleIndex index) {
        Set<String> visitedRoles = [roleName]
        Queue<String> rolesToVisit = [roleName] as Queue
        Map<String, EffectiveMember> ret = [:].withDefault { new EffectiveMember(it) }

        String current
        while ((current = rolesToVisit.poll()) != null) {
            index.rolesWithMemberRole[current]?.each { inheritedRole ->
                ret[inheritedRole].sourceRoles << current
                if (visitedRoles.add(inheritedRole)) rolesToVisit << inheritedRole
            }
        }

        ret.values().toList()
    }

    private List<EffectiveMember> getEffectiveRoles(String roleName, RoleIndex index) {
        Set<String> visitedRoles = [roleName]
        Queue<String> rolesToVisit = [roleName] as Queue
        Map<String, EffectiveMember> ret = [:].withDefault { new EffectiveMember(it) }

        String current
        while ((current = rolesToVisit.poll()) != null) {
            // Skip references to roles that do not exist, as DefaultRoleService does.
            index.roles[current].findAll { index.roles.containsKey(it) }.each { memberName ->
                ret[memberName].sourceRoles << current
                if (visitedRoles.add(memberName)) rolesToVisit << memberName
            }
        }

        ret.values().toList()
    }

    private List<EffectiveUser> getEffectiveUsers(
        List<EffectiveMember> effectiveAssignedUsers,
        List<EffectiveMember> effectiveGroups,
        Map<String, Set<String>> usersForDirectoryGroups
    ) {
        Map<String, EffectiveUser> ret = [:].withDefault { new EffectiveUser(it) }

        // 1) Copy over the assigned users, with sources
        if (defaultRoleService.userAssignmentSupported) {
            effectiveAssignedUsers.each { assignUser ->
                def retUser = ret[assignUser.name]
                assignUser.sourceRoles.each { role ->
                    retUser.addSource(role, null)
                }
            }
        }

        // 2) Lookup users from directory, with appropriate sources
        if (defaultRoleService.directoryGroupsSupported) {
            effectiveGroups.each { group ->
                usersForDirectoryGroups[group.name]?.each { user ->
                    def retUser = ret[user.toLowerCase()]
                    group.sourceRoles.each { role ->
                        retUser.addSource(role, group.name)
                    }
                }
            }
        }
        return ret.values().toList()
    }

    private List<EffectiveMember> getEffectiveMembers(
        String roleName,
        List<EffectiveMember> effectiveRoles,
        Map<String, List<String>> membersByRole
    ) {
        Map<String, EffectiveMember> ret = [:].withDefault { new EffectiveMember(it) }

        // Get members from the input roles
        membersByRole[roleName].each { memberName ->
            ret[memberName].sourceRoles << roleName
        }

        // Get members from effective roles
        effectiveRoles.each { sourceRole ->
            String sourceRoleName = sourceRole.name
            membersByRole[sourceRoleName].each { memberName ->
                ret[memberName].sourceRoles << sourceRoleName
            }
        }

        ret.values().toList()
    }


    private void ensureEnabled() {
        if (!enabled) {
            throw new RuntimeException("Action not enabled - the Hoist-provided DefaultRoleService must be used to enable role management via this service")
        }
    }

    //---------------------
    // Internal classes
    //---------------------
    /**
     * Member names of each role, read from Role.members once per listing, plus a reverse lookup of
     * the roles that list each role as a member. The graph walks above run once per role and
     * would otherwise rescan the members of every role at each step.
     */
    private static class RoleIndex {
        final Map<String, List<String>> users = [:],
                                        directoryGroups = [:],
                                        roles = [:],
                                        rolesWithMemberRole = [:]

        RoleIndex(List<Role> allRoles) {
            allRoles.each { role ->
                users[role.name] = role.users
                directoryGroups[role.name] = role.directoryGroups
                roles[role.name] = role.roles
            }
            allRoles.each { role ->
                roles[role.name].each { memberName ->
                    rolesWithMemberRole.computeIfAbsent(memberName) { [] } << role.name
                }
            }
        }
    }

    private static class EffectiveMember implements JSONFormat {
        String name
        List<String> sourceRoles = []

        EffectiveMember(String name) {
            this.name = name
        }

        Map formatForJSON() {[
            name: name,
            sourceRoles: sourceRoles
        ]}
    }

    private static class EffectiveUser implements JSONFormat {
        String name
        List<Source> sources = []

        EffectiveUser(String name) {
            this.name = name
        }

        void addSource(String role, String directoryGroup) {
            sources << new Source(role, directoryGroup)
        }

        Map formatForJSON() {[
            name   : name,
            sources: sources
        ]}
    }

    private static class Source implements JSONFormat {
        String role
        String directoryGroup

        Source(String role, String directoryGroup) {
            this.role = role
            this.directoryGroup = directoryGroup
        }

        Map formatForJSON() {
            Map ret = [role: role]
            if (directoryGroup) ret.directoryGroup = directoryGroup
            return ret
        }
    }
}

