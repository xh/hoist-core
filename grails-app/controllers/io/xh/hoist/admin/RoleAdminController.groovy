package io.xh.hoist.admin

import io.xh.hoist.BaseController
import io.xh.hoist.role.BaseRoleService
import io.xh.hoist.role.Role
import io.xh.hoist.role.RoleAdminService
import io.xh.hoist.security.Access

@Access(['HOIST_ROLE_MANAGER'])
class RoleAdminController extends BaseController {
    RoleAdminService roleAdminService
    BaseRoleService roleService

    @Access(['HOIST_ADMIN_READER'])
    def list() {
        List<Map> roles = roleAdminService.list()
        renderJSON(data:roles)
    }

    def create() {
        ensureAuthUserCanEdit()
        Map roleSpec = parseRequestJSON()
        Role role = roleAdminService.create(roleSpec)
        renderJSON(data:role)
    }

    def update() {
        ensureAuthUserCanEdit()
        Map roleSpec = parseRequestJSON()
        Role role = roleAdminService.update(roleSpec)
        renderJSON(data:role)
    }

    def delete(String id) {
        ensureAuthUserCanEdit()
        roleAdminService.delete(id)
        renderJSON(success:true)
    }

    def usersForDirectoryGroup(String name) {
        renderJSON(data:roleService.getUsersForDirectoryGroups(new HashSet([name]))[name])
    }

    //-----------------------
    // Implementation
    //-----------------------

    private void ensureAuthUserCanEdit() {
        if (!authUser.hasRole('HOIST_ROLE_MANAGER')) {
            throw new RuntimeException("$authUsername is not a 'HOIST_ROLE_MANAGER'")
        }
    }
}