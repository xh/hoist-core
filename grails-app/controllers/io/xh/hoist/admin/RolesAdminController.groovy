package io.xh.hoist.admin

import grails.gorm.transactions.ReadOnly
import io.xh.hoist.role.Role
import io.xh.hoist.security.Access

@Access(['HOIST_ADMIN_READER'])
class RolesAdminController extends AdminRestController {

//    TODO: this needs to update when the underlying does
    static restTarget = Role
    static trackChanges = true


//    def lookupData() {
//        renderJSON (
//            groupNames: Role.list().collect{it.groupName}.unique().sort()
//        )
//    }
    @ReadOnly
    def allCurrentRoles() {
        renderJSON(
            Role.list().collect{it.name}
        )
    }
}