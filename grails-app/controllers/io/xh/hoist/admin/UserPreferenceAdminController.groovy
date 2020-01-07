/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2019 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin

import grails.gorm.transactions.Transactional
import io.xh.hoist.pref.Preference
import io.xh.hoist.pref.UserPreference
import io.xh.hoist.RestController
import io.xh.hoist.security.Access
import org.grails.web.json.JSONObject

@Access(['HOIST_ADMIN'])
class UserPreferenceAdminController extends RestController {

    static restTarget = UserPreference
    static trackChanges = true

    @Transactional
    def lookupData() {
        renderJSON (
                names: Preference.list().collect{it.name}.sort()
        )
    }

    @Transactional
    protected void preprocessSubmit(JSONObject submit) {
        if (submit.name) {
            submit.preference = Preference.findByName(submit.name)
        }

        submit.lastUpdatedBy = username
    }

    @Transactional
    protected List doList(Map query) {
        return UserPreference.findAll {
            if (query.name)  preference == Preference.findByName(query.name)
            if (query.username) username =~ "%$query.username%"
        }
    }

}
