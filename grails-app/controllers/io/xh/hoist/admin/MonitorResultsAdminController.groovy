/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin

import io.xh.hoist.BaseController
import io.xh.hoist.cluster.ClusterJsonRequest
import io.xh.hoist.security.Access
import static io.xh.hoist.util.Utils.getAppContext

@Access(['HOIST_ADMIN_READER'])
class MonitorResultsAdminController extends BaseController {

    def monitorService

    def results() {
        renderJSON(monitorService.getResults())
    }

    @Access(['HOIST_ADMIN'])
    def forceRunAllMonitors() {
        runOnPrimary(new ForceRunAllMonitors())
    }
    static class ForceRunAllMonitors extends ClusterJsonRequest {
        def doCall() {
            appContext.monitorService.forceRun()
            [success: true]
        }
    }
}
