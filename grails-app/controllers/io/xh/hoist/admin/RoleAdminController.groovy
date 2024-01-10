package io.xh.hoist.admin

import io.xh.hoist.BaseController
import io.xh.hoist.role.provided.Role
import io.xh.hoist.security.Access

import static java.util.Collections.singleton

@Access(['HOIST_ROLE_MANAGER'])
class RoleAdminController extends BaseController {

    def roleAdminService,
        roleService

    @Access(['HOIST_ADMIN_READER'])
    def config() {
        renderJSON(roleService.adminConfig)
    }

    @Access(['HOIST_ADMIN_READER'])
    def list() {
        List<Map> roles = roleAdminService.list()
        renderJSON(data: roles)
    }

    def create() {
        ensureHoistRoleManager()
        Map roleSpec = parseRequestJSON()
        Role role = roleAdminService.create(roleSpec)
        renderJSON(data: role)
    }

    def update() {
        ensureHoistRoleManager()
        Map roleSpec = parseRequestJSON()
        Role role = roleAdminService.update(roleSpec)
        renderJSON(data: role)
    }

    def delete(String id) {
        ensureHoistRoleManager()
        roleAdminService.delete(id)
        renderJSON(success: true)
    }

    def usersForDirectoryGroup(String name) {
        renderJSON(data: roleService.getUsersForDirectoryGroups(singleton(name))[name])
    }


    //-----------------------
    // Implementation
    //-----------------------
    private void ensureHoistRoleManager() {
        if (!authUser.hasRole('HOIST_ROLE_MANAGER')) {
            throw new RuntimeException("AuthUser $authUsername is not a 'HOIST_ROLE_MANAGER'")
        }
    }
}
