/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.admin.cluster


import io.xh.hoist.cluster.ClusterRequest
import io.xh.hoist.security.Access


@Access(['HOIST_ADMIN_READER'])
class EnvAdminController extends BaseClusterController {

    def index(String instance) {
        runOnInstance(new Index(), instance)
    }
    static class Index extends ClusterRequest {
        def doCall() {
            [
                environment: System.getenv(),
                properties: System.properties.collectEntries {
                   [it.key.toString(), it.value.toString()]
                }
            ]
        }
    }

}
