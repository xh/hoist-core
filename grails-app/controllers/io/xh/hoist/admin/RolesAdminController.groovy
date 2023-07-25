package io.xh.hoist.admin

import io.xh.hoist.role.Role
import io.xh.hoist.security.Access

@Access(['HOIST_ADMIN_READER'])
class RolesAdminController extends AdminRestController {

    static restTarget = Role
    static trackChanges = true


//    def lookupData() {
//        renderJSON (
//            groupNames: Role.list().collect{it.groupName}.unique().sort()
//        )
//    }
}