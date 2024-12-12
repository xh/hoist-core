/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2022 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.admin.cluster

import io.xh.hoist.BaseController
import io.xh.hoist.cluster.ClusterJsonRequest
import io.xh.hoist.security.Access

import static io.xh.hoist.util.Utils.appContext

@Access(['HOIST_ADMIN_READER'])
class DistributedObjectAdminController extends BaseController {

    def getDistributedObjectsReport(String instance) {
        runOnInstance(new GetDistributedObjectsReport(), instance)
    }

    static class GetDistributedObjectsReport extends ClusterJsonRequest {
        def doCall() {
            appContext.distributedObjectAdminService.getDistributedObjectsReport()
        }
    }

    @Access(['HOIST_ADMIN'])
    def clearObjects(String instance) {
        runOnInstance(new ClearObjects(names: params.list('names')), instance)
    }

    static class ClearObjects extends ClusterJsonRequest {
        List<String> names

        def doCall() {
            appContext.distributedObjectAdminService.clearObjects(names)
            return [success: true]
        }
    }

    @Access(['HOIST_ADMIN'])
    def clearHibernateCaches(String instance) {
        runOnInstance(new ClearHibernateCaches(), instance)
    }

    static class ClearHibernateCaches extends ClusterJsonRequest {
        def doCall() {
            appContext.distributedObjectAdminService.clearHibernateCaches()
            return [success: true]
        }
    }
}
