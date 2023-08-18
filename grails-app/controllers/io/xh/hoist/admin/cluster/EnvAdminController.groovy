/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.admin.cluster

import io.xh.hoist.cluster.ClusterTask
import io.xh.hoist.security.Access


@Access(['HOIST_ADMIN_READER'])
class EnvAdminController extends BaseClusterController {

    def index() {
        runOnMember(new Index())
    }
    static class Index extends ClusterTask {
        def doCall() {
            [
                environment: System.getenv(),
                properties: System.getProperties()
            ]
        }
    }

}
