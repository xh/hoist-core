/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2025 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin.cluster

import io.xh.hoist.BaseController
import io.xh.hoist.security.Access
import static io.xh.hoist.util.ClusterUtils.runOnInstanceAsJson

@Access(['HOIST_ADMIN_READER'])
class MemoryMonitorAdminController extends BaseController {

    def memoryMonitoringService

    def snapshots(String instance) {
        def ret = runOnInstanceAsJson(memoryMonitoringService.&getSnapshots, instance)
        renderClusterJSON(ret)
    }

    @Access(['HOIST_ADMIN'])
    def takeSnapshot(String instance) {
        def ret = runOnInstanceAsJson(memoryMonitoringService.&takeSnapshot, instance)
        renderClusterJSON(ret)
    }

    @Access(['HOIST_ADMIN'])
    def requestGc(String instance) {
        def ret = runOnInstanceAsJson(memoryMonitoringService.&requestGc, instance)
        renderClusterJSON(ret)
    }

    @Access(['HOIST_ADMIN'])
    def dumpHeap(String filename, String instance) {
        def ret = runOnInstanceAsJson(memoryMonitoringService.&dumpHeap, instance, [filename])
        renderClusterJSON(ret)
    }

    def availablePastInstances() {
        renderJSON(memoryMonitoringService.availablePastInstances())
    }

    def snapshotsForPastInstance(String instance) {
        renderJSON(memoryMonitoringService.snapshotsForPastInstance(instance))
    }
}
