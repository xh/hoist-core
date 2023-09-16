/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.admin

import io.xh.hoist.BaseController
import io.xh.hoist.security.Access

@Access(['HOIST_ADMIN_READER'])
class ConnectionPoolMonitorAdminController extends BaseController {

    def connectionPoolMonitoringService

    def index() {
        def svc = connectionPoolMonitoringService
        renderJSON(
            enabled: svc.enabled,
            snapshots: svc.snapshots,
            poolConfiguration: svc.poolConfiguration
        )
    }

    @Access(['HOIST_ADMIN'])
    def takeSnapshot() {
        renderJSON(connectionPoolMonitoringService.takeSnapshot())
    }

    @Access(['HOIST_ADMIN'])
    def resetStats() {
        renderJSON(connectionPoolMonitoringService.resetStats())
    }

}
