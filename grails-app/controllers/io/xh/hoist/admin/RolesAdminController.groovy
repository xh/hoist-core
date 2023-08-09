package io.xh.hoist.admin

import grails.gorm.transactions.ReadOnly
import io.xh.hoist.BaseController
import io.xh.hoist.role.Role
import io.xh.hoist.security.Access
import io.xh.hoist.user.BaseUserService
import io.xh.toolbox.user.RoleService

@Access(['HOIST_ADMIN_READER'])
class RolesAdminController extends BaseController {
    RoleService roleService

//    TODO: this needs to update when the underlying does
    static restTarget = Role
    static trackChanges = true

    @ReadOnly
    def index() {
        renderJSON(
            Role.findAll(sort: 'name', order: 'asc').collect{[
                name: it.name,
                groupName: it.groupName,
                lastUpdated: it.lastUpdated,
                lastUpdatedBy: it.lastUpdatedBy,
            ]}
        )
    }

    @ReadOnly
    def roleDetails() {
        def roleName = params.get('roleName')
        renderJSON(
            // TODO: need to handle the case where this doesn't get...
            Role.get(roleName)
        )
    }

    @ReadOnly
    def allCurrentRoles() {
        renderJSON(
            Role.list().collect{it.name}
        )
    }

    @ReadOnly
    def effectiveChanges() {
        def changeType = params.get('changeType')
        println("changeType = " + changeType)
        if (changeType == "delete") {
            def roleName = params.get('roleName') as String
            println("roleName = " + roleName)
            return renderJSON(roleService.getImpactDelete(roleName))
        }
        renderJSON("hi")
    }
}