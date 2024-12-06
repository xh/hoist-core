/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin.cluster

import io.xh.hoist.BaseController
import io.xh.hoist.cluster.ClusterConsistencyCheckService
import io.xh.hoist.security.Access

@Access(['HOIST_ADMIN_READER'])
class ClusterConsistencyAdminController extends BaseController {

    def clusterConsistencyCheckService

    def getDistributedObjectsReport() {
        renderJSON(clusterConsistencyCheckService.getDistributedObjectsReport())
    }

    def listDistributedObjects(String instance) {
        runOnInstance(new ClusterConsistencyCheckService.ListDistributedObjects(), instance)
    }
}
