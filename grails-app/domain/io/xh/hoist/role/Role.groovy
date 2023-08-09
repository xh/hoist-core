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
            inheritedRoles: allInheritedRoles,
            allUsers:  allUsers,
            directoryGroups: directoryGroups,
            lastUpdated: lastUpdated,
            lastUpdatedBy: lastUpdatedBy,
            color: color
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

        // each user's "reason" should be the role closest to `this`
        users.unique{it.user}
    }

    List<Map<String, String>> getAllInheritedRoles() {
        def parents = roleBFS {it.children}
        def roleReasons = parents.collectMany{
            it.parentReasons
        }

        // each role's "reason" should be the role closest to `this`
        roleReasons.unique{it.role}
    }

    List<Map<String, String>> getUserReasons() {
        this.users.collect{
            ['user': it, 'reason': this.name]
        }
    }

    List<Map<String, String>> getParentReasons() {
        this.children.collect{
            ['role': it.name, 'reason': this.name, 'color': it.color]
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

    String getColor() {
        def gen = new Random()
        gen.setSeed(Long.valueOf(this.name.hashCode()))
        return "hsl(${Math.abs(gen.nextInt() % 360)}, 80%, 70%)"
    }


}
