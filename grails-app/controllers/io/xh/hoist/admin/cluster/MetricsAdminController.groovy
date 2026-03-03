/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.admin.cluster

import io.xh.hoist.BaseController
import io.xh.hoist.security.AccessRequiresRole

import io.xh.hoist.json.JSONSerializer

import static io.xh.hoist.util.ClusterUtils.runOnAllInstances

@AccessRequiresRole('HOIST_ADMIN_READER')
class MetricsAdminController extends BaseController {

    def metricsAdminService
    def metricsService

    def listMetrics() {
        def results = runOnAllInstances(metricsAdminService.&listMetrics)
        def merged = results.values()
            .findAll { !it.exception }
            .collectMany { it.value }
        renderJSON(merged)
    }

    @AccessRequiresRole('HOIST_ADMIN')
    def setPublished() {
        def body = parseRequestJSON()
        metricsService.updatePublishedMetrics(body.names as List<String>, body.published as boolean)
        renderJSON(success: true)
    }
}
