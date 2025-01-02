/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2025 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.admin.cluster

import io.xh.hoist.BaseController
import io.xh.hoist.security.Access

@Access(['HOIST_ADMIN_READER'])
class DistributedObjectAdminController extends BaseController {
    def distributedObjectAdminService

    def getDistributedObjectsReport() {
        renderJSON(distributedObjectAdminService.getDistributedObjectsReport())
    }

    @Access(['HOIST_ADMIN'])
    def clearHibernateCaches() {
        def req = parseRequestJSON()
        distributedObjectAdminService.clearHibernateCaches(req.names)
        renderJSON([success: true])
    }

    @Access(['HOIST_ADMIN'])
    def clearAllHibernateCaches() {
        distributedObjectAdminService.clearHibernateCaches()
        renderJSON([success: true])
    }
}
