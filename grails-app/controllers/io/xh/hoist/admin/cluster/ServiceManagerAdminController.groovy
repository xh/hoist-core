/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin.cluster

import io.xh.hoist.cluster.ClusterTask
import io.xh.hoist.security.Access

import static io.xh.hoist.util.Utils.appContext

@Access(['HOIST_ADMIN_READER'])
class ServiceManagerAdminController extends BaseClusterController {

    def listServices() {
        runOnMember(new ListServices())
    }
    static class ListServices extends ClusterTask {
        def doCall() {
            appContext.serviceManagerService.listServices()
        }
    }

    @Access(['HOIST_ADMIN'])
    def clearCaches() {
        def names = params.names instanceof String ? [params.names] : params.names,
            task = new ClearCaches(names: names)
        params.instance ? runOnMember(task) : runOnAllMembers(task)
    }
    static class ClearCaches extends ClusterTask {
        List<String> names

        def doCall() {
            appContext.serviceManagerService.clearCaches(names)
            return [success: true]
        }
    }
}