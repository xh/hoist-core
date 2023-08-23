/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2022 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.admin.cluster

import io.xh.hoist.cluster.ClusterTask
import io.xh.hoist.security.Access

import static io.xh.hoist.util.Utils.appContext


@Access(['HOIST_ADMIN_READER'])
class HzObjectAdminController extends BaseClusterController {

    def listObjects() {
        runOnMember(new ListObjects())
    }
    static class ListObjects extends ClusterTask {
        def doCall() {
            appContext.clusterAdminService.listObjects()
        }
    }

    @Access(['HOIST_ADMIN'])
    def clearObjects() {
        def names = params.names instanceof String ? [params.names] : params.names
        runOnMember(new ClearObjects(names: names))
    }
    static class ClearObjects extends ClusterTask {
        List<String> names

        def doCall() {
            appContext.clusterAdminService.clearObjects(names)
            return [success: true]
        }
    }
}