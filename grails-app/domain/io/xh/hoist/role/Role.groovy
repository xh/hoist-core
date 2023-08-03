package io.xh.hoist.role

import io.xh.hoist.json.JSONFormat

class Role implements JSONFormat {
    String name
    String groupName = "default"
    String notes
    Set<Role> inherits = []
    Set<String> users = []
    Set<String> directoryGroups = []

    Date lastUpdated
    String lastUpdatedBy

    static hasMany = [inherits: Role]

    static mapping = {
        table 'xh_roles'
        cache true
    }

    static constraints = {
        name(maxSize: 30, unique: true, blank: false)
        groupName(maxSize: 30, blank: false)
        notes(nullable: true, maxSize: 1200)

        lastUpdatedBy(nullable: true, maxSize: 50)
    }

    Map formatForJSON() {
        return [
            roleId:id,
            name: name,
            groupName: groupName,
            notes: notes,
            inherits: inherits.collect {it.name },
            assignedUsers: users,
            allUsers:  allUsers,
            directoryGroups: directoryGroups,
            lastUpdated: lastUpdated,
            lastUpdatedBy: lastUpdatedBy,
        ]
    }

//    rename allUsers elsewhere (maybe don't call this getAllUsers -- don't make properties that are expensive?)
//    TODO: make this this keeps the (lowest? / highest?) inheritor of a role...
    List<Map<String, String>> getAllUsers() {
        List<Map<String, String>> assignedUsers = this.users.collect{['user': it, 'reason': this.name]}
        List<Map<String, String>> inheritedUsers = this.inherits.collect{it.allUsers}.flatten()

        return ((assignedUsers?:[]) + (inheritedUsers?:[])).unique {it.user}
    }

}
