package io.xh.hoist.user

import io.xh.hoist.json.JSONFormat

class EffectiveMember implements JSONFormat {
    String name
    List<String> sourceRoles = []

    Map formatForJSON() {
        [
            name: name,
            sourceRoles: sourceRoles
        ]
    }
}
