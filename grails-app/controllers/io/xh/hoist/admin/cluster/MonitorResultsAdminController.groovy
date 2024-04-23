/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin.cluster

import io.xh.hoist.cluster.ClusterRequest
import io.xh.hoist.security.Access

import static io.xh.hoist.util.Utils.getAppContext

@Access(['HOIST_ADMIN_READER'])
class MonitorResultsAdminController extends BaseClusterController {

    def monitoringService

    def results() {
        renderJSON(monitoringService.getResults())
    }

    @Access(['HOIST_ADMIN'])
    def forceRunAllMonitors() {
        runOnPrimary(new ForceRunAllMonitors())
    }
    static class ForceRunAllMonitors extends ClusterRequest {
        def doCall() {
            appContext.monitoringService.forceRun()
        }
    }
}
