package io.xh.hoist.admin

import grails.gorm.transactions.ReadOnly
import io.xh.hoist.BaseController
import io.xh.hoist.json.JSONParser
import io.xh.hoist.role.Role
import io.xh.hoist.security.Access
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
                notes: it.notes,
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
    def allGroups() {
        renderJSON(
            Role.list().collect{it.groupName}.unique()
        )
    }

    @ReadOnly
    def cascadeImpact() {
        def changeType = params.get('changeType')
        println("changeType = " + changeType)
        if (changeType == "delete") {
            String roleName = params.get('roleName')
            println("roleName = " + roleName)
            return renderJSON(Role.get(roleName).getImpactDelete())
        } else if (changeType == "edit") {
            String roleName = params.get('roleName')
            List<String> users = JSONParser.parseArray(params.get('users'))
            List<String> inheritedRoles = JSONParser.parseArray(params.get('inheritedRoles'))

            return renderJSON((Role.get(roleName).getImpactEdit(roleName, users, inheritedRoles)))
        }
        // don't care about groupName or notes
        renderJSON("no cascade impact observed")
    }
}