/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin

import io.xh.hoist.log.LogLevel
import io.xh.hoist.security.AccessRequiresRole

@AccessRequiresRole('HOIST_ADMIN_READER')
class LogLevelAdminController extends AdminRestController {

    static restTarget = LogLevel
    def logLevelService

    protected void preprocessSubmit(Map submit) {
        if (submit.level == 'None') {
            submit.level = null
        }
        submit.lastUpdatedBy = authUsername
    }

    def lookupData() {
        renderJSON(['None'] + LogLevel.LEVELS)
    }

    protected void doCreate(Object obj, Object data) {
        super.doCreate(obj, data)
        logLevelService.calculateAdjustments()
    }

    protected void doUpdate(Object obj, Object data) {
        super.doUpdate(obj, data)
        logLevelService.calculateAdjustments()
    }

    protected void doDelete(Object obj) {
        super.doDelete(obj)
        logLevelService.calculateAdjustments()
    }

}
