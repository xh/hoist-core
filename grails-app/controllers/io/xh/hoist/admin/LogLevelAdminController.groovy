/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2022 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin

import io.xh.hoist.log.LogLevel
import io.xh.hoist.security.Access

@Access(['HOIST_ADMIN_READER'])
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
        def levels =  ['None'] + LogLevel.LEVELS
        renderJSON (levels: levels)
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
