/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2020 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin

import io.xh.hoist.pref.Preference
import io.xh.hoist.pref.UserPreference
import io.xh.hoist.RestController
import io.xh.hoist.security.Access
import org.grails.web.json.JSONObject

@Access(['HOIST_ADMIN'])
class UserPreferenceAdminController extends RestController {

    static restTarget = UserPreference
    static trackChanges = true

    def lookupData() {
        renderJSON (
                names: Preference.list().collect{it.name}.sort()
        )
    }

    protected void preprocessSubmit(JSONObject submit) {
        if (submit.name) {
            submit.preference = Preference.findByName(submit.name)
        }

        submit.lastUpdatedBy = username
    }

    protected List doList(Map query) {
        return UserPreference.findAll {
            if (query.name)  preference == Preference.findByName(query.name)
            if (query.username) username =~ "%$query.username%"
        }
    }

}
