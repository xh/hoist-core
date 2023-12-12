package io.xh.hoist.role

import io.xh.hoist.json.JSONFormat
import io.xh.hoist.user.EffectiveMember

/**
 * Backing domain class for Hoist's built-in role management. Methods on this class are used
 * internally by Hoist and should not be called directly by application code.
 */
class Role implements JSONFormat {
    String name
    String category
    String notes
    Date lastUpdated
    String lastUpdatedBy
    static hasMany = [members: RoleMember]

    static mapping = {
        table 'xh_role'
        id name: 'name', generator: 'assigned', type: 'string'
        cache true
        members cascade: 'all-delete-orphan', fetch: 'join', cache: true
    }

    static constraints = {
        category nullable: true, maxSize: 30, blank: false
        notes nullable: true, maxSize: 1200
        lastUpdatedBy maxSize: 50
    }

    Map formatForJSON() {
        Map<RoleMember.Type, List<EffectiveMember>> effectiveMembers = resolveEffectiveMembers()
        [
            name: name,
            category: category,
            notes: notes,
            users:  users,
            directoryGroups: directoryGroups,
            roles: roles,
            inheritedRoles: listInheritedRoles(),
            effectiveUsers: effectiveMembers[RoleMember.Type.USER],
            effectiveDirectoryGroups: effectiveMembers[RoleMember.Type.DIRECTORY_GROUP],
            effectiveRoles: effectiveMembers[RoleMember.Type.ROLE],
            lastUpdated: lastUpdated,
            lastUpdatedBy: lastUpdatedBy,
            members: members
        ]
    }

    List<String> getUsers() {
        members.findAll { it.type == RoleMember.Type.USER }.collect { it.name }
    }

    List<String> getDirectoryGroups() {
        members.findAll { it.type == RoleMember.Type.DIRECTORY_GROUP }.collect { it.name }
    }

    List<String> getRoles() {
        members.findAll { it.type == RoleMember.Type.ROLE }.collect { it.name }
    }

    Map<RoleMember.Type, List<EffectiveMember>> resolveEffectiveMembers() {
        Map<RoleMember.Type, List<EffectiveMember>> ret = [:]
        List<EffectiveMember> effectiveRoles = listEffectiveRoles()

        ret.put(RoleMember.Type.USER, listEffectiveUsers(effectiveRoles))
        ret.put(RoleMember.Type.DIRECTORY_GROUP, listEffectiveDirectoryGroups(effectiveRoles))
        ret.put(RoleMember.Type.ROLE, effectiveRoles)

        return ret
    }

    //------------------------
    // Implementation
    //------------------------

    /**
     * List users, each with a list of role-names justifying why they inherit this role
     */
    private List<EffectiveMember> listEffectiveUsers(List<EffectiveMember> effectiveRoles) {
        collectEffectiveMembers(effectiveRoles) { it.users }
    }

    /**
     * List directory groups, each with a list of role-names justifying why they inherit this role
     */
    private List<EffectiveMember> listEffectiveDirectoryGroups(List<EffectiveMember> effectiveRoles) {
        collectEffectiveMembers(effectiveRoles) { it.directoryGroups }
    }

    /**
     * List effective members of this role with source associations
     */
    private List<EffectiveMember> listEffectiveRoles() {
        Set<String> visitedRoles = [name]
        Queue<Role> rolesToVisit = new LinkedList<Role>()
        rolesToVisit.offer(this)
        Map<String, EffectiveMember> ret = [:].withDefault { new EffectiveMember([name: it])}

        while (!rolesToVisit.isEmpty()) {
            Role role = rolesToVisit.poll()
            role.roles.each { memberName ->
                ret[memberName].sourceRoles << role.name
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
    private List<EffectiveMember> listInheritedRoles() {
        Set<String> visitedRoles = [name]
        Queue<Role> rolesToVisit = [this] as Queue
        List<Role> allRoles = list()
        Map<String, EffectiveMember> ret = [:].withDefault { new EffectiveMember([name: it])}

        while (!rolesToVisit.isEmpty()) {
            Role role = rolesToVisit.poll()
            allRoles
                .findAll { it.roles.contains(role.name) }
                .each { inheritedRole ->
                    ret[inheritedRole.name].sourceRoles << role.name
                    if (!visitedRoles.contains(inheritedRole.name)) {
                        visitedRoles.add(inheritedRole.name)
                        rolesToVisit.offer(inheritedRole)
                    }
                }
        }

        ret.values() as List<EffectiveMember>
    }

    /**
     * Implementation for `listEffectiveUsers` and `listEffectiveDirectoryGroups`
     */
    private List<EffectiveMember> collectEffectiveMembers(
        List<EffectiveMember> sourceRoles,
        Closure<List<String>> memberNamesFn
    ) {
        Map<String, EffectiveMember> ret = [:].withDefault { new EffectiveMember([name: it])}

        memberNamesFn(this).each { memberName ->
            ret[memberName].sourceRoles << name
        }
        sourceRoles.each { sourceRole ->
            String sourceRoleName = sourceRole.name
            memberNamesFn(get(sourceRoleName)).each { memberName ->
                ret[memberName].sourceRoles << sourceRoleName
            }
        }

        ret.values() as List<EffectiveMember>
    }
}
