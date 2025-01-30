/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2025 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin

import com.jayway.jsonpath.Configuration
import com.jayway.jsonpath.JsonPath
import io.xh.hoist.BaseService
import io.xh.hoist.config.AppConfig
import io.xh.hoist.jsonblob.JsonBlob
import io.xh.hoist.pref.Preference
import io.xh.hoist.pref.UserPreference

import static com.jayway.jsonpath.Option.ALWAYS_RETURN_LIST
import static com.jayway.jsonpath.Option.AS_PATH_LIST
import static com.jayway.jsonpath.Option.SUPPRESS_EXCEPTIONS

/**
 * Service to provide searching of Hoist artifacts that persist JSON data via JSON Path expressions.
 *
 * Includes support for searching the contents of:
 *   - JSONBlobs
 *   - AppConfigs of type json
 *   - UserPreferences of type json
 *
 * Used by the search tools added to the Hoist Admin Console in Hoist React v73.
 * See https://github.com/json-path/JsonPath/blob/master/README.md for syntax help.
 */
class JsonSearchService extends BaseService {
    private Configuration matchSearchConf = Configuration
        .builder()
        .options(SUPPRESS_EXCEPTIONS, ALWAYS_RETURN_LIST)
        .build()

    private Configuration nodeSearchPathsConf = Configuration
        .builder()
        .options(AS_PATH_LIST, ALWAYS_RETURN_LIST)
        .build()

    private Configuration nodeSearchValuesConf = Configuration
        .builder()
        .options(ALWAYS_RETURN_LIST)
        .build()

    List<Map> searchBlobs(String path) {
        List<JsonBlob> matches = JsonBlob.findAllByArchivedDate(0)
            .findAll { hasPathMatch(it.value, path) }

        return matches.collect {
            [
                id         : it.id,
                type       : it.type,
                token      : it.token,
                name       : it.name,
                owner      : it.owner,
                lastUpdated: it.lastUpdated,
                json       : it.value
            ]
        }
    }

    List<Map> searchConfigs(String path) {
        List<AppConfig> results = AppConfig.findAllByValueType('json')
            .findAll { hasPathMatch(it.value, path) }

        return results.collect {
            [
                id         : it.id,
                name       : it.name,
                groupName  : it.groupName,
                lastUpdated: it.lastUpdated,
                json       : it.value
            ]
        }
    }

    List<Map> searchUserPreferences(String path) {
        List<Preference> jsonPrefs = Preference.findAllByType('json')
        List<UserPreference> results = UserPreference.findAllByPreferenceInList(jsonPrefs)
            .findAll { hasPathMatch(it.userValue, path) }

        return results.collect {
            [
                id         : it.id,
                name       : it.preference.name,
                groupName  : it.preference.groupName,
                owner      : it.username,
                lastUpdated: it.lastUpdated,
                json       : it.userValue
            ]
        }
    }

    Map findMatchingNodes(String json, String path) {
        return [
            paths : JsonPath.using(nodeSearchPathsConf).parse(json).read(path),
            values: JsonPath.using(nodeSearchValuesConf).parse(json).read(path)
        ]
    }

    private boolean hasPathMatch(String json, String path) {
        def result = JsonPath.using(matchSearchConf).parse(json).read(path)
        return result.size() > 0
    }
}
