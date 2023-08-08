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
            inherits: children.collect {it.name },
            assignedUsers: users,
            allUsers:  allUsers,
            directoryGroups: directoryGroups,
            lastUpdated: lastUpdated,
            lastUpdatedBy: lastUpdatedBy,
        ]
    }

    List<Role> findParents() {
        return Role.list().findAll{it.children.contains(this)}
    }

//    rename allUsers elsewhere (maybe don't call this getAllUsers -- don't make properties that are expensive?)
    // just get the corresponding roles first, calculate users after
    List<Map<String, String>> getAllUsers() {
        def roles = [this] + roleBFS {it.findParents()}
        def users = roles.collectMany{
            it.userReasons
        }

        // each user's "reason" should be the roll closest to `this`
        users.unique{it.user}
    }

    List<Map<String, String>> getUserReasons() {
        this.users.collect{
            ['user': it, 'reason': this.name]
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
}
