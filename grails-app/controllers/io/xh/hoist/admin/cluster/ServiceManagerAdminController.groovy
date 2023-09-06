/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.admin.cluster

import io.xh.hoist.cluster.ClusterRequest
import io.xh.hoist.security.Access

import static io.xh.hoist.util.Utils.appContext

@Access(['HOIST_ADMIN_READER'])
class ServiceManagerAdminController extends BaseClusterController {

    def listServices(String instance) {
        runOnInstance(new ListServices(), instance)
    }
    static class ListServices extends ClusterRequest {
        def doCall() {
            appContext.serviceManagerService.listServices()
        }
    }

    @Access(['HOIST_ADMIN'])
    def clearCaches(String instance) {
        def task = new ClearCaches(names: params.list('names'))
        instance ? runOnInstance(task, instance) : runOnAllInstances(task)
    }
    static class ClearCaches extends ClusterRequest {
        List<String> names

        def doCall() {
            appContext.serviceManagerService.clearCaches(names)
            return [success: true]
        }
    }
}