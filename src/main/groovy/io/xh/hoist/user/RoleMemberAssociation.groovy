package io.xh.hoist.user

import io.xh.hoist.json.JSONFormat

class RoleMemberAssociation implements JSONFormat {
    String name
    List<String> roles

    Map formatForJSON() {
        [
            name: name,
            roles: roles
        ]
    }
}
