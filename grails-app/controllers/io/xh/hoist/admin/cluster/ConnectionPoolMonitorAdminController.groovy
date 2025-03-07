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
class ConnectionPoolMonitorAdminController extends BaseController {

    def connectionPoolMonitoringService

    def snapshots(String instance) {
        def ret = connectionPoolMonitoringService.runOnInstance('getSnapshotsForAdmin', instance: instance, asJson: true)
        renderClusterJSON(ret)
    }

    @Access(['HOIST_ADMIN'])
    def takeSnapshot(String instance) {
        def ret = connectionPoolMonitoringService.runOnInstance('takeSnapshot', instance: instance, asJson: true)
        renderClusterJSON(ret)
    }

    @Access(['HOIST_ADMIN'])
    def resetStats(String instance) {
        def ret = connectionPoolMonitoringService.runOnInstance('resetStats', instance: instance, asJson: true)
        renderClusterJSON(ret)
    }
}
