/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.admin.cluster

import io.xh.hoist.BaseController
import io.xh.hoist.security.AccessRequiresRole

import static io.xh.hoist.util.ClusterUtils.runOnInstanceAsJson


@AccessRequiresRole('HOIST_ADMIN_READER')
class EnvAdminController extends BaseController {

    def serviceManagerService

    def index(String instance) {
        def ret = runOnInstanceAsJson(serviceManagerService.&getEnvironmentProperties, instance)
        renderClusterJSON(ret)
    }
}
