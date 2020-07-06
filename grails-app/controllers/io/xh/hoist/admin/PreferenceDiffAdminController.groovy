/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2020 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin

import io.xh.hoist.BaseController
import io.xh.hoist.pref.Preference
import io.xh.hoist.json.JSONParser
import io.xh.hoist.security.Access

@Access(['HOIST_ADMIN'])
class PreferenceDiffAdminController extends BaseController {

    def preferenceDiffService

    def records() {
        def data = Preference.list()
        renderJSON(data: data)
    }

    def applyRemoteValues() {
        def records = params.get('records')
        preferenceDiffService.applyRemoteValues(JSONParser.parseArray(records))

        renderJSON(success: true)
    }
}
