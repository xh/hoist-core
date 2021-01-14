package io.xh.hoist.admin

import io.xh.hoist.BaseController
import io.xh.hoist.security.Access

@Access(['HOIST_ADMIN'])
class MemoryMonitorAdminController extends BaseController {

    def memoryMonitoringService

    def snapshots() {
        renderJSON(memoryMonitoringService.snapshots)
    }

    def takeSnapshot() {
        renderJSON(memoryMonitoringService.takeSnapshot())
    }

    def requestGc() {
        renderJSON(memoryMonitoringService.requestGc())
    }

}
