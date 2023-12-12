package io.xh.hoist.admin

import grails.gorm.transactions.ReadOnly
import grails.gorm.transactions.Transactional
import io.xh.hoist.BaseService
import io.xh.hoist.role.Role
import io.xh.hoist.role.RoleMember
import io.xh.hoist.track.TrackService
import io.xh.hoist.user.RoleMemberChanges

class RoleAdminService extends BaseService {
    TrackService trackService

    @ReadOnly
    List<Role> read() {
        Role.list()
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

                roleSpec.users.each { user -> createdRole.addToMembers([
                    type: RoleMember.Type.USER,
                    name: user,
                    createdBy: 'hoist-bootstrap'
                ]) }

                roleSpec.directoryGroups.each { directoryGroup -> createdRole.addToMembers([
                    type: RoleMember.Type.DIRECTORY_GROUP,
                    name: directoryGroup,
                    createdBy: 'hoist-bootstrap'
                ]) }

                roleSpec.roles.each { role -> createdRole.addToMembers([
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
}
