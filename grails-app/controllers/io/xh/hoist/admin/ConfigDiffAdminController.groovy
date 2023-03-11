/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2022 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin

import grails.gorm.transactions.ReadOnly
import io.xh.hoist.BaseController
import io.xh.hoist.config.AppConfig
import io.xh.hoist.json.JSONParser
import io.xh.hoist.security.Access

@Access(['HOIST_ADMIN_READER'])
class ConfigDiffAdminController extends BaseController {

    def configDiffService

    @ReadOnly
    def configs() {
        def data = AppConfig.list()
        renderJSON(data: data)
    }

    @Access(['HOIST_ADMIN'])
    def applyRemoteValues() {
        def records = params.get('records')
        configDiffService.applyRemoteValues(JSONParser.parseArray(records))

        renderJSON(success: true)
    }

}
