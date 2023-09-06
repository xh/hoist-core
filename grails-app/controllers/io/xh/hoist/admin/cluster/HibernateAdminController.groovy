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
class HibernateAdminController extends BaseClusterController {

    def listCaches(String instance) {
        runOnInstance(new ListCaches(), instance)
    }
    static class ListCaches extends ClusterRequest {
        def doCall() {
            appContext.hibernateAdminService.listCaches()
        }
    }

    @Access(['HOIST_ADMIN'])
    def clearCaches(String instance) {
        runOnInstance(new ClearCaches(names: params.list('names')), instance)
    }
    static class ClearCaches extends ClusterRequest {
        List<String> names

        def doCall() {
            appContext.hibernateAdminService.clearCaches(names)
            return [success: true]
        }
    }
}