package io.xh.hoist.admin

import io.xh.hoist.BaseController
import io.xh.hoist.role.provided.DefaultRoleService
import io.xh.hoist.role.provided.Role
import io.xh.hoist.security.Access

import static java.util.Collections.singleton

@Access(['HOIST_ROLE_MANAGER'])
class RoleAdminController extends BaseController {

    def defaultRoleAdminService,
        defaultRoleEditService,
        roleService

    @Access(['HOIST_ADMIN_READER'])
    def config() {
        def ret = roleService instanceof DefaultRoleService ?
            [enabled: true, *: defaultRoleAdminService.clientConfig] :
            [enabled: false]

        renderJSON(ret)
    }

    @Access(['HOIST_ADMIN_READER'])
    def list() {
        List<Map> roles = defaultRoleAdminService.list()
        renderJSON(data: roles)
    }

    def create() {
        ensureHoistRoleManager()
        Map roleSpec = parseRequestJSON()
        Role role = defaultRoleEditService.create(roleSpec)
        renderJSON(data: role)
    }

    def update() {
        ensureHoistRoleManager()
        Map roleSpec = parseRequestJSON()
        Role role = defaultRoleEditService.update(roleSpec)
        renderJSON(data: role)
    }

    def delete(String id) {
        ensureHoistRoleManager()
        defaultRoleEditService.delete(id)
        renderJSON(success: true)
    }

    def usersForDirectoryGroup(String name) {
        renderJSON(data: roleService.loadUsersForDirectoryGroups(singleton(name))[name])
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
