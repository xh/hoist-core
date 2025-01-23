/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2025 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin

import com.jayway.jsonpath.JsonPath
import com.jayway.jsonpath.ReadContext
import io.xh.hoist.BaseController
import io.xh.hoist.jsonblob.JsonBlob
import io.xh.hoist.security.Access

@Access(['HOIST_ADMIN_READER'])
class JsonBlobSearchAdminController extends BaseController {

    def searchByJsonPath() {
        List<JsonBlob> results = JsonBlob.list().findAll { entry ->
            ReadContext ctx = JsonPath.parse(entry.value)
            try {
                def result = ctx.read(params.path)
                logTrace(result)
                return result != null
            } catch (e) {
                return false
            }
        }

        def ret = results.collect { it ->
            [type: it.type, token: it.token, name: it.name, owner: it.owner, lastUpdated: it.lastUpdated, json: it.value]
        }
        renderJSON(ret)
    }

    def getMatchingNodes(String json, String path) {
        ReadContext ctx = JsonPath.parse(json)
        def ret = ctx.read(path)
        renderJSON(ret)
    }

}
