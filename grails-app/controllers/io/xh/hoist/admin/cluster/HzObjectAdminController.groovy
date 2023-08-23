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
            appContext.clusterAdminService.objectStats
        }
    }
}