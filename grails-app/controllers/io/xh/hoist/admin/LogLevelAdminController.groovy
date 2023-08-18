/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin

import io.xh.hoist.log.LogLevel
import io.xh.hoist.security.Access

@Access(['HOIST_ADMIN_READER'])
class LogLevelAdminController extends AdminRestController {

    static restTarget = LogLevel

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
}
