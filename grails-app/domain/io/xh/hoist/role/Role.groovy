package io.xh.hoist.role

import io.xh.hoist.json.JSONFormat
import io.xh.hoist.user.EffectiveMember

class Role implements JSONFormat {
    String name
    String category = "default"
    String notes
    boolean undeletable = false // hoistRequired ? required? frameworkRequired?
    Date lastUpdated
    String lastUpdatedBy
    static hasMany = [members: RoleMember]

    static mapping = {
        table 'xh_roles'
        id name: 'name', generator: 'assigned', type: 'string'
        cache true
        members cascade: 'all-delete-orphan'
    }

    static constraints = {
        category nullable: true, maxSize: 30, blank: false
        notes nullable: true, maxSize: 1200
        lastUpdatedBy nullable: true, maxSize: 50
        lastUpdated nullable: true
    }

    Map formatForJSON() {
        List<EffectiveMember> effectiveRoles = listEffectiveRoles()
        [
            name: name,
            category: category,
            notes: notes,
            users:  users,
            directoryGroups: directoryGroups,
            roles: roles,
            inheritedRoles: listInheritedRoles(),
            effectiveUsers: listEffectiveUsers(effectiveRoles),
            effectiveDirectoryGroups: listEffectiveDirectoryGroups(effectiveRoles),
            effectiveRoles: effectiveRoles,
            lastUpdated: lastUpdated,
            lastUpdatedBy: lastUpdatedBy,
            members: members.collect { it.formatForJSON() },
            undeletable: undeletable
        ]
    }

    List<String> getUsers() { //todo - consider deducing from effective members
        members.findAll { it.type == RoleMember.Type.USER }.collect { it.name } // todo - findAllByType didn't work
    }

    List<String> getDirectoryGroups() {
        members.findAll { it.type == RoleMember.Type.DIRECTORY_GROUP }.collect { it.name }
    }

    List<String> getRoles() {
        members.findAll { it.type == RoleMember.Type.ROLE }.collect { it.name }
    }

    /**
     * List users, each with a list of role-names justifying why they inherit this role
     */
    List<EffectiveMember> listEffectiveUsers(List<EffectiveMember> effectiveRoles) {
        collectEffectiveMembers(effectiveRoles) { it.users }
    }

    /**
     * List directory groups, each with a list of role-names justifying why they inherit this role
     */
    List<EffectiveMember> listEffectiveDirectoryGroups(List<EffectiveMember> effectiveRoles) {
        collectEffectiveMembers(effectiveRoles) { it.directoryGroups }
    }

    /**
     * List effective members of this role with source associations
     */
    List<EffectiveMember> listEffectiveRoles() {
        Set<String> visitedRoles = [name]
        Queue<Role> rolesToVisit = new LinkedList<Role>()
        rolesToVisit.offer(this)
        Map<String, EffectiveMember> ret = [:].withDefault { new EffectiveMember([name: it])}

        while (!rolesToVisit.isEmpty()) {
            Role role = rolesToVisit.poll()
            role.roles.each { memberName ->
                ret[memberName].sources << role.name
                if (!visitedRoles.contains(memberName)) {
                    visitedRoles.add(memberName)
                    rolesToVisit.offer(get(memberName))
                }
            }
        }

        ret.values() as List<EffectiveMember>
    }

    /**
     * Use BFS to find all roles for which this role is an effective member, with source association
     */
    List<EffectiveMember> listInheritedRoles() {
        Set<String> visitedRoles = [name]
        Queue<Role> rolesToVisit = [this] as Queue
        List<Role> allRoles = list()
        Map<String, EffectiveMember> ret = [:].withDefault { new EffectiveMember([name: it])}

        while (!rolesToVisit.isEmpty()) {
            Role role = rolesToVisit.poll()
            allRoles
                .findAll { it.roles.contains(role.name) }
                .each { inheritedRole ->
                    ret[inheritedRole.name].sources << role.name
                    if (!visitedRoles.contains(inheritedRole.name)) {
                        visitedRoles.add(inheritedRole.name)
                        rolesToVisit.offer(inheritedRole)
                    }
                }
        }

        ret.values() as List<EffectiveMember>
    }

    //------------------------
    // Implementation
    //------------------------

    /**
     * Implementation for `listEffectiveUsers` and `listEffectiveDirectoryGroups`
     */
    private List<EffectiveMember> collectEffectiveMembers(
        List<EffectiveMember> sourceRoles,
        Closure<List<String>> memberNamesFn
    ) {
        Map<String, EffectiveMember> ret = [:].withDefault { new EffectiveMember([name: it])}

        memberNamesFn(this).each { memberName ->
            ret[memberName].sources << name
        }
        sourceRoles.each { sourceRole ->
            String sourceRoleName = sourceRole.name
            memberNamesFn(get(sourceRoleName)).each { memberName ->
                ret[memberName].sources << sourceRoleName
            }
        }

        ret.values() as List<EffectiveMember>
    }
}
