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
class ClusterObjectsAdminController extends BaseController {
    def clusterObjectsService

    def getClusterObjectsReport() {
        renderJSON(clusterObjectsService.getClusterObjectsReport())
    }

    @Access(['HOIST_ADMIN'])
    def clearHibernateCaches() {
        def req = parseRequestJSON()
        clusterObjectsService.clearHibernateCaches(req.names)
        renderJSON([success: true])
    }

    @Access(['HOIST_ADMIN'])
    def clearAllHibernateCaches() {
        clusterObjectsService.clearHibernateCaches()
        renderJSON([success: true])
    }
}
