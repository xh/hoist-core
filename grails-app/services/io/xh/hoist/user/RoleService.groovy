package io.xh.hoist.user

import grails.gorm.transactions.ReadOnly
import grails.gorm.transactions.Transactional
import io.xh.hoist.role.Role
import io.xh.hoist.role.RoleMember
import io.xh.hoist.track.TrackService

/**
 * Every user in Toolbox is granted the base APP_READER role by default - this ensures that any
 * newly created users logging in via OAuth can immediately access the app.
 *
 * Other roles (HOIST_ADMIN) are sourced from a soft-configuration map of role -> username[].
 */
class RoleService extends BaseRoleService {
    TrackService trackService

    Role create(Map roleSpec) {
        createOrUpdate(roleSpec)
    }

    @ReadOnly
    List<Role> read() {
        Role.list()
    }

    Role update(Map roleSpec) {
        createOrUpdate(roleSpec, true)
    }


    @Transactional
    void delete(Serializable id) {
        Role roleToDelete = Role.get(id)
        Map logData = roleToDelete.formatForJSON().asImmutable()

        RoleMember
            .list()
            .findAll { it.type == RoleMember.Type.ROLE && it.name == id }
            .each { it.role.removeFromMembers(it) }

        roleToDelete.delete(flush:true)

        trackService.track(
            msg: "Deleted role: '${id}'",
            category: 'Audit',
            data: logData
        )
    }

    @Override
    Map<String, Set<String>> getAllRoleAssignments() {
        read().collectEntries { role ->
            Set<String> users = new HashSet<String>()
            List<EffectiveMember> effectiveRoles = role.listEffectiveRoles()
            users.addAll(role.listEffectiveUsers(effectiveRoles).collect { it.name })
            users.addAll(role.listEffectiveDirectoryGroups(effectiveRoles).collect {
                listUsersForDirectoryGroup(it.name)
            }.flatten() as String[])

            return [role.name, users]
        }
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
            role = Role.get(roleSpec.name as Serializable)

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
