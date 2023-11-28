package io.xh.hoist.user

import grails.gorm.DetachedCriteria
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

    List<Role> read() {
        Role.list()
    }

    Role update(Map roleSpec) {
        createOrUpdate(roleSpec, true)
    }


    void delete(Serializable id) {
        Role roleToDelete = Role.get(id)
        if (roleToDelete.undeletable) {
            throw new RuntimeException("${id} cannot be deleted.")
        }
        roleToDelete.delete(flush:true)
    }

    //------------------------
    // Implementation
    //------------------------

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

        assignMembers(role, RoleMember.Type.USER, users)
        assignMembers(role, RoleMember.Type.DIRECTORY_GROUP, directoryGroups)
        assignMembers(role, RoleMember.Type.ROLE, roles)

        role.save(flush: true)

        return role
    }

    private void assignMembers(Role owner, RoleMember.Type type, Collection<String> members) {
        members.each {
            RoleMember roleMember = RoleMember.findOrCreateWhere([
                role: owner,
                type: type,
                name: it,
                lastUpdatedBy: authUsername
            ])
            roleMember.save(flush: true)
        }
        RoleMember.list()
            .find({ it.role == owner && it.type == type && !(it.name in members) })
            .each { it.delete(flush: true) }
    }
}
