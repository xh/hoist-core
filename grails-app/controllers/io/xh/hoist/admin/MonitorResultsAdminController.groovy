/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin

import io.xh.hoist.BaseController
import io.xh.hoist.security.AccessRequiresRole

import static io.xh.hoist.util.ClusterUtils.runOnPrimaryAsJson

@AccessRequiresRole('HOIST_ADMIN_READER')
class adMonitorResultsAdminController extends BaseController {

    def monitorService

    def results() {
        renderJSON(monitorService.getResults())
    }

    @AccessRequiresRole('HOIST_ADMIN')
    def forceRunAllMonitors() {
        def ret = runOnPrimaryAsJson(monitorService.&forceRun)
        renderClusterJSON(ret)
    }
}
