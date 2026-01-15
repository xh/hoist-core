/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin

import grails.gorm.transactions.ReadOnly
import io.xh.hoist.BaseController
import io.xh.hoist.config.AppConfig
import io.xh.hoist.json.JSONParser
import io.xh.hoist.security.AccessRequiresRole

@AccessRequiresRole('HOIST_ADMIN_READER')
class ConfigDiffAdminController extends BaseController {

    def configDiffService

    @ReadOnly
    def configs() {
        def data = AppConfig.list()
        renderJSON(data: data)
    }

    @AccessRequiresRole('HOIST_ADMIN')
    def applyRemoteValues() {
        def records = params.get('records')
        configDiffService.applyRemoteValues(JSONParser.parseArray(records))
        renderSuccess()
    }

}
