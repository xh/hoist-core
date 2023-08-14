package io.xh.hoist.admin

import grails.gorm.transactions.ReadOnly
import grails.gorm.transactions.Transactional
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
        if (changeType == "delete") {
            String roleName = params.get('roleName')
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

    @Transactional
    def deleteRole() {
        def mostRecentRoleUpdate = Role.createCriteria().get {
            projections {
                max "lastUpdated"
            }
        }.getTime()
        if ((params.get('timestamp') as Long) < mostRecentRoleUpdate) {
            return renderJSON("could not delete role, stale impact warning")
        }
        String roleName = params.get('roleName')
        Role role = Role.get(roleName)

        role.children.clear()
        role.findParents().collect {
            it.removeFromChildren(role)
        }
        role.save(flush:true)
        role.delete(flush:true)
    }

    @Transactional
    def updateRole() {
        def mostRecentRoleUpdate = Role.createCriteria().get {
            projections {
                max "lastUpdated"
            }
        }.getTime()
        if ((params.get('timestamp') as Long) < mostRecentRoleUpdate) {
            return renderJSON("could not update role, stale impact warning")
        }

        String roleName = params.get('roleName')
        Role role = Role.get(roleName)

        String groupName = params.get('groupName')
        String notes = params.get('notes')
        List<String> users = JSONParser.parseArray(params.get('users'))
        List<String> inheritedRoles = JSONParser.parseArray(params.get('inheritedRoles'))

        role.groupName = groupName
        role.notes = notes
        role.users = users
        role.children = inheritedRoles

        role.save(flush:true)
    }
}