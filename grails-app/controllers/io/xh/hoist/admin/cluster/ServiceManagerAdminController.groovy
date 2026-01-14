/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.admin.cluster

import io.xh.hoist.BaseController
import io.xh.hoist.security.AccessRequiresRole

import static io.xh.hoist.util.ClusterUtils.runOnInstanceAsJson
import static io.xh.hoist.util.ClusterUtils.runOnAllInstancesAsJson

@AccessRequiresRole('HOIST_ADMIN_READER')
class ServiceManagerAdminController extends BaseController {

    def serviceManagerService

    def listServices(String instance) {
        def ret = runOnInstanceAsJson(serviceManagerService.&listServices, instance)
        renderClusterJSON(ret)
    }

    def getStats(String instance, String name) {
        def ret = runOnInstanceAsJson(serviceManagerService.&getStats, instance, [name])
        renderClusterJSON(ret)
    }

    @AccessRequiresRole('HOIST_ADMIN')
    def clearCaches(String instance) {
        def names = params.list('names')
        if (instance) {
            def ret = runOnInstanceAsJson(serviceManagerService.&clearCaches, instance, [names])
            renderClusterJSON(ret)
        } else {
            def ret = runOnAllInstancesAsJson(serviceManagerService.&clearCaches, [names])
            renderJSON(ret)
        }
    }
}
