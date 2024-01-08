package io.xh.hoist.role

import io.xh.hoist.json.JSONFormat

class RoleMember implements JSONFormat {
    enum Type {
        USER,
        DIRECTORY_GROUP,
        ROLE
    }

    Type type
    String name
    static belongsTo = [role: Role]

    Date dateCreated
    String createdBy

    static mapping = {
        table 'xh_role_member'
        cache true
    }

    static constraints = {
        name unique: ['type', 'role'], blank: false
        createdBy blank: false
    }

    Map formatForJSON() {
        return [
            type: type.name(),
            name: name,
            dateCreated: dateCreated,
            createdBy: createdBy
        ]
    }
}
