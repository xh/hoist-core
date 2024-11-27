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

import static grails.async.Promises.task
import static io.xh.hoist.util.DateTimeUtils.SECONDS
import static java.lang.Thread.sleep

@Access(['HOIST_ADMIN_READER'])
class ClusterConsistencyAdminController extends BaseController {

    def clusterConsistencyCheckService

    def runClusterConsistencyCheck() {
        renderJSON(clusterConsistencyCheckService.runClusterConsistencyCheck())
    }

    def listClusterConsistencyChecks() {
        renderJSON(clusterConsistencyCheckService.listClusterConsistencyChecks())
    }
}
