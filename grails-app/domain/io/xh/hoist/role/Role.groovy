package io.xh.hoist.role

import io.xh.hoist.json.JSONFormat

class Role implements JSONFormat {
    String name
    String groupName = "default"
    String notes
    static hasMany = [inherits: Role]

    Set<String> users = []
    Set<String> directoryGroups = []

    Date lastUpdated
    String lastUpdatedBy

    boolean undeletable = false

    static mapping = {
        table 'xh_roles'
        id name: 'name', generator: 'assigned', type: 'string'
        cache true
        inherits joinTable: [name: 'xh_roles_members', key: 'role', column: 'inherits']
        users joinTable: [name: 'xh_roles_members', key: 'role', column: 'user']
        directoryGroups joinTable: [name: 'xh_roles_members', key: 'role', column: 'directory_group']
    }

    static constraints = {
        groupName nullable: true, maxSize: 30, blank: false
        notes nullable: true, maxSize: 1200
        lastUpdatedBy nullable: true, maxSize: 50
        lastUpdated nullable: true
    }

    Map formatForJSON() {
        List<String> inheritedBy = listInheritedBy(),
            directlyInheritedBy = listDirectlyInheritedBy(),
            allInheritedRoles = listAllInheritedRoles()

        return [
            name: name,
            groupName: groupName,
            notes: notes,
            inherits: inherits.collect { it.name },
            allInheritedRoles: allInheritedRoles,
            inheritanceMap: allInheritedRoles.collectEntries { [it.role, it.inheritedBy] },
            inheritedBy: inheritedBy,
            indirectlyInheritedBy: inheritedBy - directlyInheritedBy,
            directlyInheritedBy: directlyInheritedBy,
            allUsers: listAllUsers(),
            allDirectoryGroups: listAllDirectoryGroups(),
            allUserNames: users,
            allDirectoryGroupNames: directoryGroups,
            users:  users,
            directoryGroups: directoryGroups,
            lastUpdated: lastUpdated,
            lastUpdatedBy: lastUpdatedBy,
            undeletable: undeletable
        ]
    }

    /**
     * Use BFS to find all inherited roles with their "inheritedBy" reason, favoring closest
     */
    List<Map> listAllInheritedRoles() {
        Set<Role> visitedRoles = [this]
        Queue<Role> rolesToVisit = [this] as Queue
        List<Map> ret = []

        while (!rolesToVisit.isEmpty()) {
            Role role = rolesToVisit.poll()
            for (inheritedRole in role.inherits) {
                if (visitedRoles.contains(inheritedRole)) continue

                visitedRoles.add(inheritedRole)
                rolesToVisit.offer(inheritedRole)
                ret << [role: inheritedRole.name, inheritedBy: role.name]
            }
        }
        return ret
    }

    /**
     * List users, each with a list of role-names justifying why they inherit this role
     */
    List<Map> listAllUsers() {
        listWithRoles { it.users }
    }

    /**
     * List directory groups, each with a list of role-names justifying why they inherit this role
     */
    List<Map> listAllDirectoryGroups() {
        listWithRoles { it.directoryGroups }
    }

    //------------------------
    // Implementation
    //------------------------

    /**
     * List role names that directly inherit this role
     */
    private List<String> listDirectlyInheritedBy() {
        list()
            .findAll { it.inherits.find { it == this } }
            .collect { it.name }
    }

    /**
     * List role names that inherit this role, either directly or indirectly
     */
    private List<String> listInheritedBy() {
        list()
            .findAll { it != this && it.listAllInheritedRoles().find { it.role == name } }
            .collect { it.name }
    }

    /**
     * Implementation for `getAllUsers` and `getAllDirectoryGroups`
     */
    private List<Map> listWithRoles(Closure<List> getListFn) {
        Map ret = getListFn(this).collectEntries { [it, [name]] }
        listInheritedBy().each { ancestor ->
            getListFn(get(ancestor)).each { entry ->
                ret[entry] = ret[entry] ?: []
                ret[entry] << ancestor
            }
        }
        ret
            .collect { name, roles -> [name: name, roles: (roles as List).sort()] }
            .sort { it.name }
    }
}
