/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2025 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.admin.cluster

import io.xh.hoist.BaseController
import io.xh.hoist.security.Access
import static io.xh.hoist.util.ClusterUtils.runOnInstance
import static io.xh.hoist.util.ClusterUtils.runOnAllInstances

@Access(['HOIST_ADMIN_READER'])
class ServiceManagerAdminController extends BaseController {

    def serviceManagerService

    def listServices(String instance) {
        def ret = runOnInstance(serviceManagerService.&listServices, instance: instance, asJson: true)
        renderClusterJSON(ret)
    }

    def getStats(String instance, String name) {
        def ret = runOnInstance(
            serviceManagerService.&getStats,
            args: [name],
            instance: instance,
            asJson: true
        )
        renderClusterJSON(ret)
    }

    @Access(['HOIST_ADMIN'])
    def clearCaches(String instance) {
        def names = params.list('names')
        if (instance) {
            def ret = runOnInstance(
                serviceManagerService.&clearCaches,
                args: [names],
                instance: instance,
                asJson: true
            )
            renderClusterJSON(ret)
        } else {
            def ret = runOnAllInstances(
                serviceManagerService.&clearCaches,
                args: [names],
                asJson: true
            )
            renderJSON(ret)
        }
    }
}
