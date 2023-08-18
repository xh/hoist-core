/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.admin.cluster

import io.xh.hoist.cluster.ClusterTask
import io.xh.hoist.security.Access

import static io.xh.hoist.util.Utils.appContext

@Access(['HOIST_ADMIN_READER'])
class ConnectionPoolMonitorAdminController extends BaseClusterController {

    def snapshots() {
        runOnMember(new Snapshots())
    }
    static class Snapshots extends ClusterTask {
        def doCall() {
            def svc = appContext.connectionPoolMonitoringService
            return [
                enabled          : svc.enabled,
                snapshots        : svc.snapshots,
                poolConfiguration: svc.poolConfiguration
            ]
        }
    }


    @Access(['HOIST_ADMIN'])
    def takeSnapshot() {
        runOnMember(new TakeSnapshot())
    }
    static class TakeSnapshot extends ClusterTask {
        def doCall() {
            appContext.connectionPoolMonitoringService.takeSnapshot()
        }
    }

    @Access(['HOIST_ADMIN'])
    def resetStats() {
        runOnMember(new ResetStats())
    }
    static class ResetStats extends ClusterTask {
        def doCall() {
            appContext.connectionPoolMonitoringService.resetStats()
        }
    }
}
