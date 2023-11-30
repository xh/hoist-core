package io.xh.hoist.user

import io.xh.hoist.json.JSONFormat

class RoleAssociation implements JSONFormat {
    String role
    String inheritedBy

    Map formatForJSON() {
        [
            role: role,
            inheritedBy: inheritedBy
        ]
    }
}
