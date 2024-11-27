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
import io.xh.hoist.util.Utils

import static io.xh.hoist.util.DateTimeUtils.SECONDS

import static grails.async.Promises.task
import static java.lang.Thread.sleep

@Access(['HOIST_ADMIN_READER'])
class ClusterAdminController extends BaseController {

    def clusterAdminService,
        trackService

    def allInstances() {
        renderJSON(clusterAdminService.allStats)
    }

    @Access(['HOIST_ADMIN'])
    def shutdownInstance(String instance) {
        trackService.track(
            category: 'Cluster Admin',
            msg: 'Initiated Instance Shutdown',
            severity: 'WARN',
            logData: true,
            data: [instance: instance]
        )
        logWarn('Initiated Instance Shutdown', [instance: instance])
        runOnInstance(new ShutdownInstance(), instance)
        // Wait enough to let async call below complete
        sleep(5 * SECONDS)
    }
    static class ShutdownInstance extends ClusterRequest {
        def doCall() {
            // Run async to allow this call to successfully return.
            task {
                sleep(1 * SECONDS)
                Utils.clusterService.shutdownInstance()
            }
            [success: true]
        }
    }
}
