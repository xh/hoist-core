package io.xh.hoist.admin

import grails.gorm.transactions.Transactional
import io.xh.hoist.BaseController
import io.xh.hoist.role.Role
import io.xh.hoist.security.Access
import io.xh.hoist.user.RoleService

// TODO - add tracking of changes to roles

class RolesAdminController extends BaseController {
    RoleService roleService

    @Access(['HOIST_ADMIN'])
    create() {
        Map roleSpec = parseRequestJSON().data as Map
        Role role = roleService.create(roleSpec)
        renderJSON(success:true, data:role)
    }

    @Access(['HOIST_ADMIN_READER'])
    read() {
        List<Role> roles = roleService.read()
        renderJSON(success:true, data:roles)
    }

    @Access(['HOIST_ADMIN'])
    update() {
        Map roleSpec = parseRequestJSON().data as Map
        Role role = roleService.update(roleSpec)
        renderJSON(success:true, data:role)
    }

    @Access(['HOIST_ADMIN'])
    delete() {
        roleService.delete(params.id as Serializable)
        renderJSON(success:true)
    }
}