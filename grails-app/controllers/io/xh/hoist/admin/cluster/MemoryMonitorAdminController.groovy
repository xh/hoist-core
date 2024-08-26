/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin.cluster

import io.xh.hoist.BaseController
import io.xh.hoist.cluster.ClusterRequest
import io.xh.hoist.security.Access

import static io.xh.hoist.util.Utils.appContext

@Access(['HOIST_ADMIN_READER'])
class MemoryMonitorAdminController extends BaseController {

    def snapshots(String instance) {
        runOnInstance(new Snapshots(), instance)
    }
    static class Snapshots extends ClusterRequest {
        def doCall() {
            appContext.memoryMonitoringService.snapshots
        }
    }

    @Access(['HOIST_ADMIN'])
    def takeSnapshot(String instance) {
        runOnInstance(new TakeSnapshot(), instance)
    }
    static class TakeSnapshot extends ClusterRequest {
        def doCall() {
            appContext.memoryMonitoringService.takeSnapshot()
        }
    }


    @Access(['HOIST_ADMIN'])
    def requestGc(String instance) {
        runOnInstance(new RequestGc(), instance)
    }
    static class RequestGc extends ClusterRequest {
        def doCall() {
            appContext.memoryMonitoringService.requestGc()
        }
    }


    @Access(['HOIST_ADMIN'])
    def dumpHeap(String filename, String instance) {
        runOnInstance(new DumpHeap(filename: filename), instance)
    }
    static class DumpHeap extends ClusterRequest {
        String filename

        def doCall() {
            appContext.memoryMonitoringService.dumpHeap(filename)
            return [success: true]
        }
    }
}