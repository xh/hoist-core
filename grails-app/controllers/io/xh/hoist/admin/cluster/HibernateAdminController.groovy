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
class HibernateAdminController extends BaseClusterController {

    def listCaches() {
        runOnMember(new ListCaches())
    }
    static class ListCaches extends ClusterTask {
        def doCall() {
            appContext.hibernateAdminService.listCaches()
        }
    }

    @Access(['HOIST_ADMIN'])
    def clearCaches() {
        def names = params.names instanceof String ? [params.names] : params.names
        runOnMember(new ClearCaches(names: names))
    }
    static class ClearCaches extends ClusterTask {
        List<String> names

        def doCall() {
            appContext.hibernateAdminService.clearCaches(names)
            return [success: true]
        }
    }
}