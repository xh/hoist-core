/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2025 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin

import com.jayway.jsonpath.Configuration
import com.jayway.jsonpath.JsonPath
import com.jayway.jsonpath.Option
import io.xh.hoist.BaseController
import io.xh.hoist.pref.Preference
import io.xh.hoist.pref.UserPreference
import io.xh.hoist.security.Access

@Access(['HOIST_ADMIN_READER'])
class PreferenceJsonSearchAdminController extends BaseController {

    def searchByJsonPath() {
        Configuration conf = Configuration.builder()
            .options(Option.ALWAYS_RETURN_LIST).build()

        List<Preference> jsonPrefs = Preference.findAllByType('json')
        List<UserPreference> userPrefs = jsonPrefs.collect { UserPreference.findAllByPreference(it)  }.flatten()
        List<UserPreference> results = userPrefs.findAll { entry ->
            def result = JsonPath.using(conf).parse(entry.userValue).read(params.path)
            return result.size() > 0
        }

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

    def getMatchingNodes(String json, String path, boolean asPathList) {
        Configuration conf = asPathList
            ? Configuration.builder().options(Option.AS_PATH_LIST, Option.ALWAYS_RETURN_LIST).build()
            : Configuration.defaultConfiguration()

        def ret = JsonPath.using(conf).parse(json).read(path)
        renderJSON(ret)
    }

}
