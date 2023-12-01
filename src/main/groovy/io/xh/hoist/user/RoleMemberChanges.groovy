package io.xh.hoist.user

class RoleMemberChanges {
    List<String> added
    List<String> removed

    RoleMemberChanges(List<String> added = [], List<String> removed = []) {
        this.added = added
        this.removed = removed
    }
}
