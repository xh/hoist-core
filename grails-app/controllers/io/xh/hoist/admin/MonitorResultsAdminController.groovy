/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2025 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin

import io.xh.hoist.BaseController
import io.xh.hoist.security.Access
import static io.xh.hoist.util.ClusterUtils.runOnPrimary

@Access(['HOIST_ADMIN_READER'])
class MonitorResultsAdminController extends BaseController {

    def monitorService

    def results() {
        renderJSON(monitorService.getResults())
    }

    @Access(['HOIST_ADMIN'])
    def forceRunAllMonitors() {
        def ret = runOnPrimary(monitorService.&forceRun, asJson: true)
        renderClusterJSON(ret)
    }
}
