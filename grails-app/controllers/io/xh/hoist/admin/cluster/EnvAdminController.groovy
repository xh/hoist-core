/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.admin.cluster

import io.xh.hoist.BaseController
import io.xh.hoist.cluster.ClusterJsonRequest
import io.xh.hoist.security.Access

import static io.xh.hoist.util.Utils.isSensitiveParamName


@Access(['HOIST_ADMIN_READER'])
class EnvAdminController extends BaseController {

    def index(String instance) {
        runOnInstance(new Index(), instance)
    }
    static class Index extends ClusterJsonRequest {
        def doCall() {
            [
                environment: System.getenv().collectEntries {
                    [it.key, isSensitiveParamName(it.key) ? '*****' : it.value]
                },
                properties: System.properties
            ]
        }
    }
}
