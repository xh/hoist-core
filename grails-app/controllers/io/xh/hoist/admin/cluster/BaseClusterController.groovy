/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin.cluster

import io.xh.hoist.BaseController
import io.xh.hoist.cluster.ClusterResponse
import io.xh.hoist.cluster.ClusterTask

abstract class BaseClusterController extends BaseController {

    def clusterService

    protected void runOnMember(ClusterTask task) {
        runOnMemberInternal(task, params.instance)
    }

    protected void runOnMaster(ClusterTask task) {
        runOnMemberInternal(task, clusterService.masterName)
    }

    protected void runOnAllMembers(ClusterTask task) {
        def ret = clusterService
            .submitToAllMembers(task)
            .collectEntries { [it.key, it.value.get()] }
        renderJSON(ret)
    }

    // -------------------------
    // Implementation
    //---------------------------
    private void runOnMemberInternal(ClusterTask task, String instance) {
        ClusterResponse ret = instance == clusterService.instanceName ?
            task.call() :
            clusterService.submitToMember(task, instance).get()

        response.setContentType('application/json; charset=UTF-8')
        response.setStatus(ret.status)
        render(ret.result)
    }
}
