/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.admin

import io.xh.hoist.BaseController
import io.xh.hoist.role.provided.DefaultRoleService
import io.xh.hoist.role.provided.Role
import io.xh.hoist.security.AccessRequiresRole

import static java.util.Collections.singleton

@AccessRequiresRole('HOIST_ROLE_MANAGER')
class RoleAdminController extends BaseController {

    def defaultRoleAdminService,
        defaultRoleUpdateService,
        roleService

    @AccessRequiresRole('HOIST_ADMIN_READER')
    def config() {
        def ret = roleService instanceof DefaultRoleService ?
            [enabled: true, *: defaultRoleAdminService.clientConfig] :
            [enabled: false]

        renderJSON(ret)
    }

    @AccessRequiresRole('HOIST_ADMIN_READER')
    def list() {
        List<Map> roles = defaultRoleAdminService.list()
        renderJSON(data: roles)
    }

    def create() {
        ensureHoistRoleManager()
        Map roleSpec = parseRequestJSON()
        Role role = defaultRoleUpdateService.create(roleSpec)
        renderJSON(data: role)
    }

    def update() {
        ensureHoistRoleManager()
        Map roleSpec = parseRequestJSON()
        Role role = defaultRoleUpdateService.update(roleSpec)
        renderJSON(data: role)
    }

    def delete() {
        ensureHoistRoleManager()
        Map roleSpec = parseRequestJSON()
        defaultRoleUpdateService.delete(roleSpec.name)
        renderSuccess()
    }

    def usersForDirectoryGroup(String name) {
        renderJSON(data: roleService.loadUsersForDirectoryGroups(singleton(name), true)[name])
    }

    def bulkCategoryUpdate() {
        ensureHoistRoleManager()
        Map body = parseRequestJSON()
        List<Role> updatedRoles = defaultRoleUpdateService.bulkCategoryUpdate(body.roles, body.category)
        renderJSON(data: updatedRoles)
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
