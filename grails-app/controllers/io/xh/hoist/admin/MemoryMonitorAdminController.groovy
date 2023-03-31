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
class MemoryMonitorAdminController extends BaseController {

    def memoryMonitoringService

    def snapshots() {
        renderJSON(memoryMonitoringService.snapshots)
    }

    @Access(['HOIST_ADMIN'])
    def takeSnapshot() {
        renderJSON(memoryMonitoringService.takeSnapshot())
    }

    @Access(['HOIST_ADMIN'])
    def requestGc() {
        renderJSON(memoryMonitoringService.requestGc())
    }

}
