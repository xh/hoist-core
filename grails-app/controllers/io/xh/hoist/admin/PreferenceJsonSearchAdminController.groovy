/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2025 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin

import io.xh.hoist.pref.Preference
import io.xh.hoist.pref.UserPreference
import io.xh.hoist.security.Access

@Access(['HOIST_ADMIN_READER'])
class PreferenceJsonSearchAdminController extends BaseJsonSearchController {

    def searchByJsonPath() {
        List<Preference> jsonPrefs = Preference.findAllByType('json')
        List<UserPreference> userPrefs = jsonPrefs.collect { UserPreference.findAllByPreference(it)  }.flatten()
        List<UserPreference> results = userPrefs.findAll { hasPathMatch(it.userValue, params.path) }

        def ret = results.collect { it ->
            [
                id: it.id,
                name: it.preference.name,
                groupName: it.preference.groupName,
                owner: it.username,
                lastUpdated: it.lastUpdated,
                json: it.userValue
            ]
        }
        renderJSON(ret)
    }

}
