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

    Date dateCreated
    String createdBy

    static mapping = {
        table 'xh_roles_members'
        cache true
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
