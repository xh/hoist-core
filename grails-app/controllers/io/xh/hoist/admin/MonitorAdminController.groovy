/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin

import io.xh.hoist.monitor.Monitor
import io.xh.hoist.monitor.MonitorMetricType
import io.xh.hoist.security.AccessRequiresRole

@AccessRequiresRole('HOIST_ADMIN_READER')
class MonitorAdminController extends AdminRestController {

    static restTarget = Monitor
    static trackChanges = true

    def lookupData() {
        renderJSON(metricTypes: MonitorMetricType.values()*.name())
    }

    protected void preprocessSubmit(Map submit) {
        submit.lastUpdatedBy = authUsername
    }
}
