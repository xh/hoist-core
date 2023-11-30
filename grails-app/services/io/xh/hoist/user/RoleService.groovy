package io.xh.hoist.user

import grails.gorm.transactions.ReadOnly
import grails.gorm.transactions.Transactional
import io.xh.hoist.role.Role
import io.xh.hoist.role.RoleMember

/**
 * Every user in Toolbox is granted the base APP_READER role by default - this ensures that any
 * newly created users logging in via OAuth can immediately access the app.
 *
 * Other roles (HOIST_ADMIN) are sourced from a soft-configuration map of role -> username[].
 */
class RoleService extends BaseRoleService {
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

        if (roleToDelete.undeletable) {
            throw new RuntimeException("${id} cannot be deleted.")
        }

        RoleMember
            .list()
            .findAll { it.type == RoleMember.Type.ROLE && it.name == id }
            .each { it.role.removeFromMembers(it) }

        roleToDelete.delete(flush:true)
    }

    //------------------------
    // Implementation
    //------------------------

    @Transactional
    private Role createOrUpdate(Map roleSpec, update = false) {
        List<String> users = roleSpec.users as List<String>,
                     directoryGroups = roleSpec.directoryGroups as List<String>,
                     roles = roleSpec.roles as List<String>

        roleSpec.remove('undeletable')
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

        updateMembers(role, RoleMember.Type.USER, users)
        updateMembers(role, RoleMember.Type.DIRECTORY_GROUP, directoryGroups)
        updateMembers(role, RoleMember.Type.ROLE, roles)

        role.setLastUpdatedBy(authUsername)
        role.save(flush: true)

        return role
    }

    private void updateMembers(Role owner, RoleMember.Type type, List<String> members) {
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
            }
        }

        existingMembers.each { member ->
            if (!members.contains(member.name)) {
                owner.removeFromMembers(member)
            }
        }
    }
}
