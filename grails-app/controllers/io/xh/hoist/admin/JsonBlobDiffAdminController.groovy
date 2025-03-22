/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2025 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin

import io.xh.hoist.BaseController
import io.xh.hoist.jsonblob.JsonBlob
import io.xh.hoist.json.JSONParser
import io.xh.hoist.security.Access

@Access(['HOIST_ADMIN_READER'])
class JsonBlobDiffAdminController extends BaseController {

    def jsonBlobDiffService

    def jsonBlobs() {
        def data = JsonBlob.list()
        renderJSON(data: data)
    }

    @Access(['HOIST_ADMIN'])
    def applyRemoteValues() {
        def records = params.get('records')
        jsonBlobDiffService.applyRemoteValues(JSONParser.parseArray(records))
        renderSuccess()
    }

}
