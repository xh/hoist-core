/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2025 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin

import com.jayway.jsonpath.JsonPath
import com.jayway.jsonpath.Configuration
import com.jayway.jsonpath.Option
import io.xh.hoist.BaseController
import io.xh.hoist.jsonblob.JsonBlob
import io.xh.hoist.security.Access

@Access(['HOIST_ADMIN_READER'])
class JsonBlobSearchAdminController extends BaseController {

    def searchByJsonPath() {
        Configuration conf = Configuration.builder()
            .options(Option.ALWAYS_RETURN_LIST).build()

        List<JsonBlob> results = JsonBlob.list().findAll { entry ->
            def result = JsonPath.using(conf).parse(entry.value).read(params.path)
            return result.size() > 0
        }

        def ret = results.collect { it ->
            [
                type: it.type,
                token: it.token,
                name: it.name,
                owner: it.owner,
                lastUpdated: it.lastUpdated,
                json: it.value
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
