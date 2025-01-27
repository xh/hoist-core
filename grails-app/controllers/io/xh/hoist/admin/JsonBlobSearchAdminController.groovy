/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2025 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin

import io.xh.hoist.jsonblob.JsonBlob
import io.xh.hoist.security.Access

@Access(['HOIST_ADMIN_READER'])
class JsonBlobSearchAdminController extends BaseJsonSearchController {

    def searchByJsonPath() {
        List<JsonBlob> results = JsonBlob.list().findAll { hasPathMatch(it.value, params.path) }

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
}
