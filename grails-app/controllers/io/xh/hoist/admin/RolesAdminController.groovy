package io.xh.hoist.admin

import grails.gorm.transactions.ReadOnly
import io.xh.hoist.BaseController
import io.xh.hoist.role.Role
import io.xh.hoist.security.Access

@Access(['HOIST_ADMIN_READER'])
class RolesAdminController extends BaseController {
//    TODO: this needs to update when the underlying does
    static restTarget = Role
    static trackChanges = true


//    def lookupData() {
//        renderJSON (
//            groupNames: Role.list().collect{it.groupName}.unique().sort()
//        )
//    }
    @ReadOnly
    def index() {
        renderJSON(
            Role.findAll(sort: 'name', order: 'asc').collect{[
                roleId: it.id,
                name: it.name,
                groupName: it.groupName,
                lastUpdated: it.lastUpdated,
                lastUpdatedBy: it.lastUpdatedBy,
                assignedUserCount: it.users.size(),
                // do we want to calculate the below size? potentially expensive
                allUserCount: it.allUsers.size()
            ]}
        )
    }

    @ReadOnly
    def roleDetails() {
        def roleId = params.get('roleId')
        renderJSON(
            Role.get(roleId)
        )
    }

    @ReadOnly
    def allCurrentRoles() {
        renderJSON(
            Role.list().collect{it.name}
        )
    }
}