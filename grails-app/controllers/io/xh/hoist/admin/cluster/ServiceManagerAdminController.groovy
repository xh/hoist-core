/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2025 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.admin.cluster

import io.xh.hoist.BaseController
import io.xh.hoist.security.Access

@Access(['HOIST_ADMIN_READER'])
class ServiceManagerAdminController extends BaseController {

    def serviceManagerService

    def listServices(String instance) {
        def ret = serviceManagerService.runOnInstance('listServices', instance: instance, asJson: true)
        renderClusterJSON(ret)
    }

    def getStats(String instance, String name) {
        def ret = serviceManagerService.runOnInstance(
            'getStats',
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
            def ret = serviceManagerService.runOnInstance(
                'clearCaches',
                args: [names],
                instance: instance,
                asJson: true
            )
            renderClusterJSON(ret)
        } else {
            def ret = serviceManagerService.runOnAllInstances(
                'clearCaches',
                args: [names],
                asJson: true
            )
            renderJSON(ret)
        }
    }
}
