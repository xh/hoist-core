/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin

import io.xh.hoist.BaseController
import io.xh.hoist.security.Access
import io.xh.hoist.util.Utils

import java.util.concurrent.Callable

@Access(['HOIST_ADMIN_READER'])
class MemoryMonitorAdminController extends BaseController {

    def memoryMonitoringService,
        clusterService

    def snapshots() {
        renderJSON(memoryMonitoringService.snapshots)
    }

    def clusterSnapshots() {
        def ret = clusterService
            .submitToAllMembers(new GetMemberSnapshot())
            .collectEntries { member, results -> [member.getAttribute('instanceName'), results.get()] }
        renderJSON(ret)
    }

    @Access(['HOIST_ADMIN'])
    def takeSnapshot() {
        renderJSON(memoryMonitoringService.takeSnapshot())
    }

    @Access(['HOIST_ADMIN'])
    def takeClusterSnapshot() {
        clusterService.executeOnAllMembers(new TakeMemberSnapshot())
        renderJSON(success: true)
    }

    @Access(['HOIST_ADMIN'])
    def requestGc() {
        renderJSON(memoryMonitoringService.requestGc())
    }

    @Access(['HOIST_ADMIN'])
    def dumpHeap(String filename) {
        memoryMonitoringService.dumpHeap(filename)
        renderJSON(success: true)
    }


    @Access(['HOIST_ADMIN'])
    def requestClusterGc() {
        renderJSON(clusterService.executeOnAllMembers(new RequestMemberGc()))
        renderJSON(success: true)
    }

    //-----------------
    // Cluster tasks
    //-----------------
    static class GetMemberSnapshot implements Callable<Map>, Serializable {
        Map call() {
            Utils.appContext.memoryMonitoringService.snapshots
        }
    }

    static class TakeMemberSnapshot implements Runnable, Serializable {
        void run() {
            Utils.appContext.memoryMonitoringService.takeSnapshot()
        }
    }

    static class RequestMemberGc implements Runnable, Serializable {
        void run() {
            Utils.appContext.memoryMonitoringService.requestGc()
        }
    }
}
