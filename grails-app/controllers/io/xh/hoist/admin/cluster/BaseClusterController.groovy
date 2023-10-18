/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin.cluster

import io.xh.hoist.BaseController
import io.xh.hoist.cluster.ClusterRequest

abstract class BaseClusterController extends BaseController {

    def clusterService

    protected void runOnInstance(ClusterRequest task, String instance) {

        // Avoid serialization/async overhead  for local call
        def isLocal = instance == clusterService.instanceName,
            ret = isLocal ? task.call() : clusterService.submitToInstance(task, instance)

        // Cluster request logs any exceptions internally/remotely. Only want to *render* here
        if (ret.exception) {
            exceptionRenderer.renderException(ret.exception, response)
        } else {
            renderJSON(ret.value)
        }
    }

    protected void runOnMaster(ClusterRequest task) {
        runOnInstance(task, clusterService.masterName)
    }

    protected void runOnAllInstances(ClusterRequest task) {
        // Cluster request logs any exceptions internally/remotely. Only want to *render* here
        def resp = clusterService.submitToAllInstances(task),
            ret = resp.collectEntries { k, v ->
                [k, v.exception ? exceptionRenderer.toJSON(v.exception) : v]
             }
        renderJSON(ret)
    }
}
