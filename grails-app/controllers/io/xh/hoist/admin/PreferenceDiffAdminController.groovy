/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2025 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin

import grails.gorm.transactions.ReadOnly
import io.xh.hoist.BaseController
import io.xh.hoist.pref.Preference
import io.xh.hoist.json.JSONParser
import io.xh.hoist.security.Access

@Access(['HOIST_ADMIN_READER'])
class PreferenceDiffAdminController extends BaseController {

    def prefDiffService

    @ReadOnly
    def preferences() {
        def data = Preference.list()
        renderJSON(data: data)
    }

    @Access(['HOIST_ADMIN'])
    def applyRemoteValues() {
        def records = params.get('records')
        prefDiffService.applyRemoteValues(JSONParser.parseArray(records))
        renderSuccess()
    }
}
