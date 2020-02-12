/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2020 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin

import io.xh.hoist.config.AppConfig
import io.xh.hoist.RestController
import io.xh.hoist.security.Access
import org.grails.web.json.JSONObject
import io.xh.hoist.json.JSON

@Access(['HOIST_ADMIN'])
class ConfigAdminController extends RestController {

    static restTarget = AppConfig
    static trackChanges = true

    def lookupData() {
        renderJSON(
                valueTypes: AppConfig.TYPES,
                groupNames: AppConfig.list().collect{it.groupName}.unique().sort()
        )
    }

    protected void preprocessSubmit(JSONObject submit) {
        def valueType = AppConfig.get(submit.id)?.valueType ?: submit.valueType
        if (valueType.equals('json')) {
            submit.value = JSON.parse(submit.value).toString(2);
        }
        submit.lastUpdatedBy = username
    }
}
