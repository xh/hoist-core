/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2025 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin.cluster

import io.xh.hoist.BaseController
import io.xh.hoist.security.Access

import static io.xh.hoist.util.ClusterUtils.runOnInstance

@Access(['HOIST_ADMIN_READER'])
class ClusterAdminController extends BaseController {

    def clusterAdminService,
        trackService

    def allInstances() {
        renderJSON(clusterAdminService.allStats)
    }

    @Access(['HOIST_ADMIN'])
    def shutdownInstance(String instance) {
        trackService.track(
            category: 'Cluster Admin',
            msg: 'Initiated Instance Shutdown',
            severity: 'WARN',
            logData: true,
            data: [instance: instance]
        )
        logWarn('Initiated Instance Shutdown', [instance: instance])
        def ret = runOnInstance(clusterService.&shutdownInstance, instance: instance, asJson: true)
        renderClusterJSON(ret)
    }
}
