/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.role.provided

import io.xh.hoist.json.JSONFormat

/**
 * Backing domain class for Role memberships, when using Hoist's built-in role management.
 */
class RoleMember implements JSONFormat {

    enum Type {USER, DIRECTORY_GROUP, ROLE}

    Type type
    String name
    Date dateCreated
    String createdBy

    static belongsTo = [role: Role]

    static mapping = {
        table 'xh_role_member'
        cache true
    }

    static constraints = {
        name unique: ['type', 'role'], blank: false
        createdBy blank: false
    }

    def beforeInsert() {
        if (type == Type.USER) name = name?.toLowerCase()
    }

    def beforeUpdate() {
        if (type == Type.USER) name = name?.toLowerCase()
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
