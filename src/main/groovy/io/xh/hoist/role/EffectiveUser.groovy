package io.xh.hoist.role

import io.xh.hoist.json.JSONFormat

class EffectiveUser implements JSONFormat {
    String name
    List<Source> sources = []

    void addSource(String role, String directoryGroup) {
        sources << new Source(role, directoryGroup)
    }

    Map formatForJSON() {[
        name: name,
        sources: sources
    ]}

    private class Source implements JSONFormat {
        String role
        String directoryGroup

        Source(String role, String directoryGroup) {
            this.role = role
            this.directoryGroup = directoryGroup
        }

        Map formatForJSON() {
            Map ret = [role: role]
            if (directoryGroup) ret.directoryGroup = directoryGroup
            return ret
        }
    }
}
