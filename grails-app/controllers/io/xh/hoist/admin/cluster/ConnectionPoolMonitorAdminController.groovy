/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.admin.cluster

import io.xh.hoist.BaseController
import io.xh.hoist.cluster.ClusterJsonRequest
import io.xh.hoist.security.Access

import static io.xh.hoist.util.Utils.appContext

@Access(['HOIST_ADMIN_READER'])
class ConnectionPoolMonitorAdminController extends BaseController {

    def snapshots(String instance) {
        runOnInstance(new Snapshots(), instance)
    }
    static class Snapshots extends ClusterJsonRequest {
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
    def takeSnapshot(String instance) {
        runOnInstance(new TakeSnapshot(), instance)
    }
    static class TakeSnapshot extends ClusterJsonRequest {
        def doCall() {
            appContext.connectionPoolMonitoringService.takeSnapshot()
        }
    }

    @Access(['HOIST_ADMIN'])
    def resetStats() {
        runOnInstance(new ResetStats())
    }
    static class ResetStats extends ClusterJsonRequest {
        def doCall() {
            appContext.connectionPoolMonitoringService.resetStats()
        }
    }
}
