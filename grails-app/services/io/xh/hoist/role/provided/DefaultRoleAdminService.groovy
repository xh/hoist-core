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
        def usersForGroups = emptyMap(),
            errorsForGroups = emptyMap()

        if (defaultRoleService.directoryGroupsSupported) {
            Set<String> groups = roles.collectMany(new HashSet()) { it.directoryGroups }
            if (groups) {
                Map<String, Object> groupsLookup = defaultRoleService.loadUsersForDirectoryGroups(groups)
                usersForGroups = groupsLookup.findAll { it.value instanceof Set }
                errorsForGroups = groupsLookup.findAll { !(it.value instanceof Set) }
            }
        }

        roles.collect {role ->
            def inheritedRoles = getInheritedRoles(role),
                effectiveRoles = getEffectiveRoles(role),
                effectiveGroups = getEffectiveMembers(role, effectiveRoles) { it.directoryGroups },
                effectiveAssignedUsers = getEffectiveMembers(role, effectiveRoles) { it.users },
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
    private List<EffectiveMember> getInheritedRoles(Role role) {
        Set<String> visitedRoles = [role.name]
        Queue<Role> rolesToVisit = [role] as Queue
        List<Role> allRoles = Role.list()
        Map<String, EffectiveMember> ret = [:].withDefault { new EffectiveMember([name: it])}

        while (role = rolesToVisit.poll()) {
            allRoles
                .findAll { it.roles.contains(role.name) }
                .each { inheritedRole ->
                    ret[inheritedRole.name].sourceRoles << role.name
                    if (!visitedRoles.contains(inheritedRole.name)) {
                        visitedRoles << inheritedRole.name
                        rolesToVisit << inheritedRole
                    }
                }
        }

        ret.values().toList()
    }

    private List<EffectiveMember> getEffectiveRoles(Role role) {
        Set<String> visitedRoles = [role.name]
        Queue<Role> rolesToVisit = [role] as Queue
        Map<String, EffectiveMember> ret = [:].withDefault { new EffectiveMember([name: it])}

        while (role = rolesToVisit.poll()) {
            role.roles.each { memberName ->
                ret[memberName].sourceRoles << role.name
                if (!visitedRoles.contains(memberName)) {
                    visitedRoles << memberName
                    rolesToVisit << Role.get(memberName)
                }
            }
        }

        ret.values().toList()
    }

    private List<EffectiveUser> getEffectiveUsers(
        List<EffectiveMember> effectiveAssignedUsers,
        List<EffectiveMember> effectiveGroups,
        Map usersForDirectoryGroups
    ) {
        Map<String, EffectiveUser> ret = [:].withDefault { new EffectiveUser([name: it]) }

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
        Role role,
        List<EffectiveMember> effectiveRoles,
        Closure<List<String>> memberNamesFn
    ) {
        Map<String, EffectiveMember> ret = [:].withDefault { new EffectiveMember([name: it]) }

        // Get members from the input roles
        memberNamesFn(role).each { memberName ->
            ret[memberName].sourceRoles << role.name
        }

        // Get members from effective roles
        effectiveRoles.each { sourceRole ->
            String sourceRoleName = sourceRole.name
            memberNamesFn(Role.get(sourceRoleName)).each { memberName ->
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
    // Internal DTO classes
    //---------------------
    private class EffectiveMember implements JSONFormat {
        String name
        List<String> sourceRoles = []

        Map formatForJSON() {[
            name: name,
            sourceRoles: sourceRoles
        ]}
    }

    private class EffectiveUser implements JSONFormat {
        String name
        List<Source> sources = []

        void addSource(String role, String directoryGroup) {
            sources << new Source(role, directoryGroup)
        }

        Map formatForJSON() {[
            name   : name,
            sources: sources
        ]}
    }

    private class Source implements JSONFormat {
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

