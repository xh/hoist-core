/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.admin.cluster

import io.xh.hoist.BaseController
import io.xh.hoist.security.AccessRequiresRole

import static io.xh.hoist.util.ClusterUtils.runOnAllInstancesAsJson
import static io.xh.hoist.util.ClusterUtils.runOnInstanceAsJson

@AccessRequiresRole('HOIST_ADMIN_READER')
class ConnectionPoolMonitorAdminController extends BaseController {

    def connectionPoolMonitoringService,
        trackService

    def snapshots(String instance) {
        def ret = runOnInstanceAsJson(connectionPoolMonitoringService.&getSnapshotsForAdmin, instance)
        renderClusterJSON(ret)
    }

    @AccessRequiresRole('HOIST_ADMIN')
    def takeSnapshot(String instance) {
        def ret = runOnInstanceAsJson(connectionPoolMonitoringService.&takeSnapshot, instance)
        renderClusterJSON(ret)
    }

    @AccessRequiresRole('HOIST_ADMIN')
    def resetStats(String instance) {
        def ret = runOnInstanceAsJson(connectionPoolMonitoringService.&resetStats, instance)
        renderClusterJSON(ret)
    }

    @AccessRequiresRole('HOIST_ADMIN')
    def purgeConnections(String instance) {
        trackService.track(
            category: 'Cluster Admin',
            msg: 'Purged connection pools',
            severity: 'WARN',
            logData: true,
            data: [instance: instance ?: 'all']
        )
        def purge = connectionPoolMonitoringService.&purgeConnections
        instance ?
            renderClusterJSON(runOnInstanceAsJson(purge, instance)) :
            renderJSON(runOnAllInstancesAsJson(purge))
    }
}
