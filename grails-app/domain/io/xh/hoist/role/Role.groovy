package io.xh.hoist.role

import grails.gorm.transactions.Transactional
import io.xh.hoist.json.JSONFormat

class Role implements JSONFormat {
    String name
    String groupName = "default"
    String notes
    static hasMany = [children: Role]

    Set<String> users = []
    Set<String> directoryGroups = []

    Date lastUpdated
    String lastUpdatedBy

    static mapping = {
        table 'xh_roles'
        id name: 'name', generator: 'assigned', type: 'string'
        cache true
    }

    static constraints = {
//        id(maxSize: 30)
        groupName(maxSize: 30, blank: false)
        notes(nullable: true, maxSize: 1200)
        lastUpdatedBy(nullable: true, maxSize: 50)
    }

    Map formatForJSON() {
        return [
            name: name,
            groupName: groupName,
            notes: notes,
            inheritedRoles: allChildrenRolesWithReasons,
            allUsers:  allUsersWithReasons,
            directoryGroups: directoryGroups,
            lastUpdated: lastUpdated,
            lastUpdatedBy: lastUpdatedBy,
        ]
    }

    List<Role> findParents() {
        return Role.list().findAll{it.children.contains(this)}
    }

    List<String> getAllUsers() {
        def parents = [this] + roleBFS {it.findParents()}
        def users = parents.collectMany{
            it.users
        }

        // each user's "reason" should be the role closest to `this` (or maybe consider
        // returning them all? re:conversation with Anselm about looking at "reasons" when
        // trying to absolve someone of a role...)
        users.unique()
    }

//    rename allUsers elsewhere (maybe don't call this getAllUsers -- don't make properties that are expensive?)
    // just get the corresponding roles first, calculate users after
    List<Map<String, String>> getAllUsersWithReasons() {
        def parents = [this] + roleBFS {it.findParents()}
        def users = parents.collectMany{
            it.userReasons()
        }

        // each user's "reason" should be the role closest to `this` (see above note)
        users.unique{it.user}
    }

    List<Role> getAllChildrenRoles() {
        roleBFS {it.children}.unique()
    }

    List<Map<String, String>> getAllChildrenRolesWithReasons() {
        def children =  roleBFS {it.children}

        children.collectMany{
            it.childReasons()
        }.unique{it.role}
    }

    List<Role> getAllParentRoles() {
        roleBFS {it.findParents()}.unique()
    }

    List<Map<String, String>> userReasons() {
        this.users.collect{
            ['user': it, 'reason': this.name]
        }
    }

    List<Map<String, String>> childReasons() {
        this.children.collect{
            ['role': it.name, 'reason': this.name]
        }
    }

    List<Role> roleBFS(Closure findNextNodes) {
        Set<Role> explored = [this]
        Queue<Role> Q = [this] as Queue

        while (!Q.isEmpty()) {
            def v = Q.poll()
            for (w in findNextNodes(v)) {
                if (!explored.contains(w)) {
                   explored.add(w)
                    Q.offer(w)
                }
            }
        }
        return explored.toList()
    }

    Map<String, Object> getImpactDelete() {
        def actingUsers = this.allUsers
        def inheritedRoles = this.allParentRoles

        [
            this: true,
            userCount: actingUsers.size(),
            inheritedRolesCount: inheritedRoles.size()
        ]
    }

    Map<String, Object> getImpactEdit(String newRoleName, List<String> newUsers, List<String> newInheritedRoles) {
        def currentUsers = this.users
        def currentInheritedRoles = this.children

        Set<Role> inheritedRolesChange = (currentInheritedRoles + newInheritedRoles) - (currentInheritedRoles.intersect(newInheritedRoles))
        Set<String> usersChange = (currentUsers + newUsers) - (currentUsers.intersect(newUsers))
        List<String> cascadeUsersChange = inheritedRolesChange.collectMany {it.allUsers}

        [
            this: this.name == newRoleName,
            // userCount needs to be the change in users, plus the cascade of changed inheritance...
            inheritedRoles: inheritedRolesChange.size(),
            userCount: (usersChange + cascadeUsersChange).toSet().size()

        ]
    }
}
