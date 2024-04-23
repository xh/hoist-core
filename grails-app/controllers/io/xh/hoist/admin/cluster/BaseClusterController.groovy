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
        def ret = clusterService.submitToInstance(task, instance)
        if (ret.exception) {
            // Just render exception, was already logged on target instance
            xhExceptionHandler.handleException(exception: ret.exception, renderTo: response)
            return
        }
        renderJSON(ret.value)
    }

    protected void runOnPrimary(ClusterRequest task) {
        runOnInstance(task, clusterService.primaryName)
    }

    protected void runOnAllInstances(ClusterRequest task) {
        renderJSON(clusterService.submitToAllInstances(task))
    }
}
