package io.xh.hoist.admin

import grails.gorm.transactions.ReadOnly
import grails.gorm.transactions.Transactional
import io.xh.hoist.BaseService
import io.xh.hoist.role.Role
import io.xh.hoist.role.RoleMember
import io.xh.hoist.user.EffectiveMember
import io.xh.hoist.user.EffectiveUser
import io.xh.hoist.user.RoleMemberChanges

/**
 * Service to support admin management of Hoist's persistent, database-backed {@link Role} class.
 *
 * This class is intended to be used by its associated admin controller - apps should rarely (if
 * ever) need to interact with it directly. An exception is {@link #ensureRequiredRolesCreated},
 * which apps might wish to call in their Bootstrap code to ensure that expected roles have been
 * created in a given database environment.
 *
 * @see io.xh.hoist.user.BaseRoleService - this is the service contract apps must implement and is
 *     by default the primary user/consumer of the Roles created and updated by the service below.
 */
class RoleAdminService extends BaseService {
    def roleService,
        trackService

    /**
     * List all Roles with all available membership information and metadata, for display in the
     * Hoist Admin Console. Includes fully resolved effective users, directory groups, and roles.
     */
    @ReadOnly
    List<Map> list() {
        List<Role> roles = Role.list()

        roles.collect {
            Map<RoleMember.Type, List<EffectiveMember>> effectiveMembers = it.resolveEffectiveMembers()
            [
                name: it.name,
                category: it.category,
                notes: it.notes,
                lastUpdated: it.lastUpdated,
                lastUpdatedBy: it.lastUpdatedBy,
                inheritedRoles: it.listInheritedRoles(),
                effectiveUsers: collectEffectiveUsers(effectiveMembers, roles),
                effectiveDirectoryGroups: effectiveMembers[RoleMember.Type.DIRECTORY_GROUP],
                effectiveRoles: effectiveMembers[RoleMember.Type.ROLE],
                members: it.members
            ]
        }
    }

    Role create(Map roleSpec) {
        createOrUpdate(roleSpec)
    }

    Role update(Map roleSpec) {
        createOrUpdate(roleSpec, true)
    }

    @Transactional
    void delete(String id) {
        Role roleToDelete = Role.get(id)

        RoleMember
            .findAllByTypeAndName(RoleMember.Type.ROLE, id)
            .each { it.role.removeFromMembers(it) }

        roleToDelete.delete(flush:true)

        trackService.track(
            msg: "Deleted role: '${id}'",
            category: 'Audit'
        )
    }


    /**
     * Check a list of core roles required for Hoist/application operation - ensuring that these
     * roles are present. Will create missing roles with supplied default values if not found.
     *
     * Called by Hoist Core Bootstrap.
     *
     * @param requiredRoles - List of maps of [name, category, notes, users, directoryGroups, roles]
     */
    @Transactional
    void ensureRequiredRolesCreated(List<Map> requiredRoles) {
        List<Role> currRoles = Role.list()
        int created = 0

        requiredRoles.each { roleSpec ->
            Role currRole = currRoles.find { it.name == roleSpec.name }
            if (!currRole) {
                Role createdRole = new Role(
                    name: roleSpec.name,
                    category: roleSpec.category ?: 'Default',
                    notes: roleSpec.notes,
                    lastUpdatedBy: 'hoist-bootstrap'
                ).save()

                roleSpec.users?.each { user -> createdRole.addToMembers([
                    type: RoleMember.Type.USER,
                    name: user,
                    createdBy: 'hoist-bootstrap'
                ]) }

                roleSpec.directoryGroups?.each { directoryGroup -> createdRole.addToMembers([
                    type: RoleMember.Type.DIRECTORY_GROUP,
                    name: directoryGroup,
                    createdBy: 'hoist-bootstrap'
                ]) }

                roleSpec.roles?.each { role -> createdRole.addToMembers([
                    type: RoleMember.Type.ROLE,
                    name: role,
                    createdBy: 'hoist-bootstrap'
                ]) }

                logWarn(
                    "Required role $roleSpec.name missing and created with default value",
                    'verify default is appropriate for this application'
                )
                created++
            }
        }

        logDebug("Validated presense of ${requiredRoles.size()} required roles", "created $created")
    }

    //------------------------
    // Implementation
    //------------------------

    @Transactional
    private Role createOrUpdate(Map roleSpec, update = false) {
        List<String> users = roleSpec.users as List<String>,
                     directoryGroups = roleSpec.directoryGroups as List<String>,
                     roles = roleSpec.roles as List<String>

        roleSpec.remove('users')
        roleSpec.remove('directoryGroups')
        roleSpec.remove('roles')

        Role role

        if (update) {
            role = Role.get(roleSpec.name as String)
            roleSpec.each { k, v -> role.setProperty(k as String, v) }
        } else {
            role = new Role(roleSpec)
        }

        RoleMemberChanges userChanges = updateMembers(role, RoleMember.Type.USER, users),
                          directoryGroupChanges = updateMembers(role, RoleMember.Type.DIRECTORY_GROUP, directoryGroups),
                          roleChanges = updateMembers(role, RoleMember.Type.ROLE, roles)

        role.setLastUpdatedBy(authUsername)
        role.save(flush: true)

        if (update) {
            trackService.track(
                msg: "Edited role: '${roleSpec.name}'",
                category: 'Audit',
                data: [
                    role: roleSpec.name,
                    category: roleSpec.category,
                    notes: roleSpec.notes,
                    addedUsers: userChanges.added,
                    removedUsers: userChanges.removed,
                    addedDirectoryGroups: directoryGroupChanges.added,
                    removedDirectoryGroups: directoryGroupChanges.removed,
                    addedRoles: roleChanges.added,
                    removedRoles: roleChanges.removed
                ]
            )
        } else {
            trackService.track(
                msg: "Created role: '${roleSpec.name}'",
                category: 'Audit',
                data: [
                    role: roleSpec.name,
                    category: roleSpec.category,
                    notes: roleSpec.notes,
                    users: userChanges.added,
                    directoryGroups: directoryGroupChanges.added,
                    roles: roleChanges.added
                ]
            )
        }

        return role
    }

    private RoleMemberChanges updateMembers(Role owner, RoleMember.Type type, List<String> members) {
        RoleMemberChanges changes = new RoleMemberChanges()

        List<RoleMember> existingMembers = RoleMember
            .list()
            .findAll { it.role == owner && it.type == type }

        members.each { member ->
            if (!existingMembers.find { it.name == member }) {
                owner.addToMembers([
                    type: type,
                    name: member,
                    createdBy: authUsername
                ])
                changes.added << member
            }
        }

        existingMembers.each { member ->
            if (!members.contains(member.name)) {
                owner.removeFromMembers(member)
                changes.removed << member.name
            }
        }

        return changes
    }

    private List<EffectiveUser> collectEffectiveUsers(
        Map<RoleMember.Type, List<EffectiveMember>> effectiveMembers,
        List<Role> roles
    ) {
        Map<String, EffectiveUser> ret = [:].withDefault { new EffectiveUser([name: it])}
        Map<String, Set<String>> usersForDirectoryGroups
        boolean enableUsers = roleService.config.enableUsers,
             enableDirectoryGroups = roleService.config.enableDirectoryGroups

        if (enableDirectoryGroups) {
            Set<String> directoryGroups = new HashSet<String>()
            roles.each { directoryGroups.addAll(it.directoryGroups) }
            usersForDirectoryGroups = roleService.getUsersForDirectoryGroups(directoryGroups)
        }

        effectiveMembers.each { type, members ->
            if (type == RoleMember.Type.ROLE) return

            members.each { member ->
                if (type == RoleMember.Type.USER && enableUsers) {
                    member.sourceRoles.each { role ->
                        ret[member.name].addSource(role, null)
                    }
                } else if (type == RoleMember.Type.DIRECTORY_GROUP && enableDirectoryGroups) {
                    usersForDirectoryGroups[member.name]?.each { user ->
                        member.sourceRoles.each { role ->
                            ret[user].addSource(role, member.name)
                        }
                    }
                }
            }
        }

        ret.values() as List<EffectiveUser>
    }
}
