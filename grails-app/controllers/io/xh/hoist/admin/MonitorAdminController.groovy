/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2021 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin

import io.xh.hoist.RestController
import io.xh.hoist.monitor.Monitor
import io.xh.hoist.security.Access

@Access(['HOIST_ADMIN'])
class MonitorAdminController extends RestController {

    def monitoringService

    static restTarget = Monitor
    static trackChanges = true

    def lookupData() {
        renderJSON(metricTypes: Monitor.METRIC_TYPES)
    }

    protected void preprocessSubmit(Map submit) {
        submit.lastUpdatedBy = username
    }

    def forceRunAllMonitors() {
        monitoringService.forceRun()
        renderJSON(success:true)
    }

    def results() {
        renderJSON(monitoringService.getResults())
    }
    
}
