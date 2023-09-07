/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin.cluster

import io.xh.hoist.cluster.ClusterRequest
import io.xh.hoist.security.Access

import static io.xh.hoist.util.DateTimeUtils.SECONDS

@Access(['HOIST_ADMIN_READER'])
class ClusterAdminController extends BaseClusterController {

    def clusterAdminService,
        trackService

    def allInstances() {
        renderJSON(clusterAdminService.allStats)
    }

    @Access(['HOIST_ADMIN'])
    def shutdownInstance(String instance) {
        trackService.track(
            msg: "Initiated Instance Shutdown",
            severity: 'WARN',
            data: [instance: instance]
        )
        logWarn('Initiated Instance Shutdown', [instance: instance])
        Thread.sleep(2 * SECONDS)
        runOnInstance(new ShutdownInstance(), instance)
    }
    static class ShutdownInstance extends ClusterRequest {
        Map doCall() {
            System.exit(0)
            [success: true]
        }
    }
}