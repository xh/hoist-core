package io.xh.hoist.role

class RoleMember {
    enum Type {
        USER,
        DIRECTORY_GROUP,
        ROLE
    }

    Type type
    String name
    static belongsTo = [role: Role]

    Date lastUpdated
    String lastUpdatedBy

    static mapping = {
        table 'xh_roles_members'
        cache true
    }

    Map formatForJSON() {
        return [
            type: type,
            name: name,
            lastUpdated: lastUpdated,
            lastUpdatedBy: lastUpdatedBy
        ]
    }
}
