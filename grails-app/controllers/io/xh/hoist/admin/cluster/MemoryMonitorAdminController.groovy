/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin.cluster

import io.xh.hoist.cluster.ClusterTask
import io.xh.hoist.security.Access

import static io.xh.hoist.util.Utils.appContext

@Access(['HOIST_ADMIN_READER'])
class MemoryMonitorAdminController extends BaseClusterController {

    def snapshots() {
        runOnMember(new Snapshots())
    }
    static class Snapshots extends ClusterTask {
        def doCall() {
            appContext.memoryMonitoringService.snapshots
        }
    }


    @Access(['HOIST_ADMIN'])
    def takeSnapshot() {
        renderJSON(runOnMember(new TakeSnapshot()))
    }
    static class TakeSnapshot extends ClusterTask {
        def doCall() {
            appContext.memoryMonitoringService.takeSnapshot()
        }
    }


    @Access(['HOIST_ADMIN'])
    def requestGc() {
        runOnMember(new RequestGc())
    }
    static class RequestGc extends ClusterTask {
        def doCall() {
            appContext.memoryMonitoringService.requestGc()
        }
    }


    @Access(['HOIST_ADMIN'])
    def dumpHeap(String filename) {
        runOnMember(new DumpHeap(filename: filename))
    }
    static class DumpHeap extends ClusterTask {
        String filename

        def doCall() {
            appContext.memoryMonitoringService.dumpHeap(filename)
            return [success: true]
        }
    }
}