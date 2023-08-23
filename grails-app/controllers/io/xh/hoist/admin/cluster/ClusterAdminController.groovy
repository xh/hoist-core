/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin.cluster

import io.xh.hoist.security.Access

@Access(['HOIST_ADMIN_READER'])
class ClusterAdminController extends BaseClusterController {

    def clusterAdminService

    def allInstances() {
        renderJSON(clusterAdminService.allStats)
    }
}