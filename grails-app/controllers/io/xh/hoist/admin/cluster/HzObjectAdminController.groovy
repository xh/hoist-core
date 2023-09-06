/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2022 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.admin.cluster

import io.xh.hoist.cluster.ClusterRequest
import io.xh.hoist.security.Access

import static io.xh.hoist.util.Utils.appContext


@Access(['HOIST_ADMIN_READER'])
class HzObjectAdminController extends BaseClusterController {

    def listObjects(String instance) {
        runOnInstance(new ListObjects(), instance)
    }
    static class ListObjects extends ClusterRequest {
        def doCall() {
            appContext.clusterAdminService.listObjects()
        }
    }

    @Access(['HOIST_ADMIN'])
    def clearObjects(String instance) {
        runOnInstance(new ClearObjects(names: params.list('names')), instance)
    }
    static class ClearObjects extends ClusterRequest {
        List<String> names

        def doCall() {
            appContext.clusterAdminService.clearObjects(names)
            return [success: true]
        }
    }
}