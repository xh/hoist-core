package io.xh.hoist.admin

import io.xh.hoist.BaseController
import io.xh.hoist.role.Role
import io.xh.hoist.security.Access

@Access(['HOIST_ADMIN'])
class RoleAdminController extends BaseController {
    RoleAdminService roleAdminService

    @Access(['HOIST_ADMIN_READER'])
    def list() {
        List<Map> roles = roleAdminService.list()
        renderJSON(data:roles)
    }

    def create() {
        Map roleSpec = parseRequestJSON()
        Role role = roleAdminService.create(roleSpec)
        renderJSON(data:role)
    }

    def update() {
        Map roleSpec = parseRequestJSON()
        Role role = roleAdminService.update(roleSpec)
        renderJSON(data:role)
    }

    def delete(String id) {
        roleAdminService.delete(id)
        renderJSON(success:true)
    }
}