/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2022 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin

import io.xh.hoist.monitor.Monitor
import io.xh.hoist.security.Access

@Access(['HOIST_ADMIN_READER'])
class MonitorAdminController extends AdminRestController {

    def monitoringService

    static restTarget = Monitor
    static trackChanges = true

    def lookupData() {
        renderJSON(metricTypes: Monitor.METRIC_TYPES)
    }

    protected void preprocessSubmit(Map submit) {
        submit.lastUpdatedBy = authUsername
    }

    @Access(['HOIST_ADMIN'])
    def forceRunAllMonitors() {
        monitoringService.forceRun()
        renderJSON(success:true)
    }

    def results() {
        renderJSON(monitoringService.getResults())
    }
    
}
