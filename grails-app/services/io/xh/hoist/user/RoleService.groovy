package io.xh.toolbox.user

import io.xh.hoist.role.Role
import io.xh.hoist.user.BaseRoleService
import grails.gorm.transactions.Transactional

import static io.xh.hoist.util.Utils.configService

/**
 * Every user in Toolbox is granted the base APP_READER role by default - this ensures that any
 * newly created users logging in via OAuth can immediately access the app.
 *
 * Other roles (HOIST_ADMIN) are sourced from a soft-configuration map of role -> username[].
 */
@Transactional
class RoleService extends BaseRoleService {

    static String READER_ROLE = 'APP_READER'

    void init() {
        def confRoles = configService.getMap('roles')
        confRoles.each{role, users ->
            updateRole(role, users.toSet())
        }
        updateRole('HOIST_IMPERSONATOR', [])
    }

//    use camel case over snake and type parameters
    private void updateRole(roleName, users) {
        def r = Role.findWhere(
            name: roleName,
            groupName: 'xh_hoist'
        )

        if (!r) {
            r = new Role(
                name: roleName,
                groupName: 'xh_hoist',
                inherits: [],
                users: [],
                directoryGroups: []
            )
        }

        def inheritance = []
        if (roleName != "HOIST_ADMIN") {
//            can just think of simpler case with only hoist-admin inheritance
            inheritance << Role.findByName("HOIST_ADMIN")
        }
        r.inherits = inheritance
        r.users = users
        r.lastUpdatedBy = "xh_role_service"
        r.save()
    }

    Map<String, Set<String>> getAllRoleAssignments() {
        def ret = new HashMap<>()
        def confRoles = configService.getMap('roles')
        confRoles.each{role, users ->
            ret[role] = users.toSet()
        }

        // All users are granted a READER_ROLE as per class doc comment.
        def allUsernames = User.list().collect{user -> user.email}
        ret.put(READER_ROLE, new HashSet(allUsernames))

        return ret
    }


}
