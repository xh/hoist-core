/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2022 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin

import io.xh.hoist.BaseController
import io.xh.hoist.security.Access
import io.xh.hoist.user.BaseRoleService
import io.xh.hoist.user.BaseUserService

@Access(['HOIST_ADMIN'])
class UserAdminController extends BaseController {

    BaseUserService userService
    BaseRoleService roleService

    def users(boolean activeOnly) {
        renderJSON(userService.list(activeOnly))
    }

    def roles() {
        renderJSON(roleService.getAllRoleAssignments())
    }

    def rolesForUser(String user) {
        renderJSON(
            user: user,
            roles: roleService.getRolesForUser(user)
        )
    }

    def usersForRole(String role) {
        renderJSON(
            role: role,
            users: roleService.getUsersForRole(role)
        )
    }

}
