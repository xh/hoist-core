/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2018 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin

import io.xh.hoist.pref.Preference
import io.xh.hoist.RestController
import io.xh.hoist.security.Access
import org.grails.web.json.JSONObject

@Access(['HOIST_ADMIN'])
class PreferenceAdminController extends RestController {

    static restTarget = Preference
    static trackChanges = true

    def lookupData() {
        renderJSON (types: Preference.TYPES)
    }

    protected void preprocessSubmit(JSONObject submit) {
        submit.lastUpdatedBy = username
    }

}
