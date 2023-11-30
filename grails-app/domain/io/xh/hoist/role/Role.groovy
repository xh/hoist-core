package io.xh.hoist.role

import io.xh.hoist.json.JSONFormat
import io.xh.hoist.user.RoleAssociation
import io.xh.hoist.user.RoleMemberAssociation

class Role implements JSONFormat {
    String name
    String groupName = "default"
    String notes
    static hasMany = [members: RoleMember]

    Date lastUpdated
    String lastUpdatedBy

    boolean undeletable = false

    static mapping = {
        table 'xh_roles'
        id name: 'name', generator: 'assigned', type: 'string'
        cache true
        members cascade: 'all-delete-orphan'
    }

    static constraints = {
        groupName nullable: true, maxSize: 30, blank: false
        notes nullable: true, maxSize: 1200
        lastUpdatedBy nullable: true, maxSize: 50
        lastUpdated nullable: true
    }

    Map formatForJSON() {
        List<RoleMemberAssociation> effectiveRoles = listEffectiveRoles()
        [
            name: name,
            groupName: groupName,
            notes: notes,
            users:  users,
            directoryGroups: directoryGroups,
            roles: roles,
            inheritedRoles: listInheritedRoles(),
            effectiveUsers: listEffectiveUsers(effectiveRoles),
            effectiveDirectoryGroups: listEffectiveDirectoryGroups(effectiveRoles),
            effectiveRoles: effectiveRoles,
            lastUpdated: lastUpdated,
            lastUpdatedBy: lastUpdatedBy,
            members: members.collect { it.formatForJSON() },
            undeletable: undeletable
        ]
    }

    List<String> getUsers() {
        members.findAll { it.type == RoleMember.Type.USER }.collect { it.name }
    }

    List<String> getDirectoryGroups() {
        members.findAll { it.type == RoleMember.Type.DIRECTORY_GROUP }.collect { it.name }
    }

    List<String> getRoles() {
        members.findAll { it.type == RoleMember.Type.ROLE }.collect { it.name }
    }

    /**
     * Use BFS to find all inherited roles with their "inheritedBy" reason, favoring closest
     */
    List<RoleAssociation> listInheritedRoles() {
        Set<Role> visitedRoles = [this]
        Queue<Role> rolesToVisit = [this] as Queue
        List<RoleAssociation> ret = []

        while (!rolesToVisit.isEmpty()) {
            Role role = rolesToVisit.poll()
            list()
                .findAll { it.roles.contains(role.name) }
                .each { inheritedRole ->
                    if (visitedRoles.contains(inheritedRole)) return
                    visitedRoles.add(inheritedRole)
                    rolesToVisit.offer(inheritedRole)
                    ret << new RoleAssociation([role: inheritedRole.name, inheritedBy: role.name])
                }
        }
        return ret
    }

    /**
     * List users, each with a list of role-names justifying why they inherit this role
     */
    List<RoleMemberAssociation> listEffectiveUsers(List<RoleMemberAssociation> effectiveRoles) {
        listWithRoles(effectiveRoles) { it.users }
    }

    /**
     * List directory groups, each with a list of role-names justifying why they inherit this role
     */
    List<RoleMemberAssociation> listEffectiveDirectoryGroups(List<RoleMemberAssociation> effectiveRoles) {
        listWithRoles(effectiveRoles) { it.directoryGroups }
    }

    /**
     * List child roles, each with a list of role-names justifying why they inherit this role
     */
    List<RoleMemberAssociation> listEffectiveRoles() {
        Set<Role> visitedRoles = [this]
        Queue<Role> rolesToVisit = [this] as Queue
        Map ret = [:]

        while (!rolesToVisit.isEmpty()) {
            Role role = rolesToVisit.poll()
            role.roles.each { childRoleName ->
                Role childRole = get(childRoleName)
                if (visitedRoles.contains(childRole)) return
                visitedRoles.add(childRole)
                rolesToVisit.offer(childRole)
                ret[childRoleName] = ret[childRoleName] ?: []
                ret[childRoleName] << role.name
            }
        }

        ret
            .collect { name, roles ->
                new RoleMemberAssociation([name: name, roles: (roles as List).sort()])
            }
            .sort { it.name }
    }

    //------------------------
    // Implementation
    //------------------------

    /**
     * Implementation for `listEffectiveUsers` and `listEffectiveDirectoryGroups`
     */
    private List<RoleMemberAssociation> listWithRoles(
        List<RoleMemberAssociation> effectiveRoles,
        Closure<List> getListFn
    ) {
        Map ret = getListFn(this).collectEntries { [it, [name]] }
        effectiveRoles.each { effectiveRole ->
            String childRoleName = effectiveRole.name
            getListFn(get(childRoleName)).each { entry ->
                ret[entry] = ret[entry] ?: []
                ret[entry] << childRoleName
            }
        }
        ret
            .collect { name, roles ->
                new RoleMemberAssociation([name: name, roles: (roles as List).sort()])
            }
            .sort { it.name }
    }
}
