/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2025 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.admin.cluster

import io.xh.hoist.BaseController
import io.xh.hoist.security.Access
import static io.xh.hoist.util.ClusterUtils.runOnInstanceAsJson


@Access(['HOIST_ADMIN_READER'])
class EnvAdminController extends BaseController {

    def serviceManagerService

    def index(String instance) {
        def ret = runOnInstanceAsJson(serviceManagerService.&getEnvironmentProperties, instance)
        renderClusterJSON(ret)
    }
}
