/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2025 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin


import io.xh.hoist.BaseController
import io.xh.hoist.security.Access

@Access(['HOIST_ADMIN_READER'])
class JsonSearchController extends BaseController {

    JsonSearchService jsonSearchService

    def searchBlobs() {
        def ret = jsonSearchService.searchBlobs(params.path)
        renderJSON(ret)
    }

    def searchUserPreferences() {
        def ret = jsonSearchService.searchUserPreferences(params.path)
        renderJSON(ret)
    }

    def getMatchingNodes(String json, String path, boolean asPathList) {
        def ret = jsonSearchService.getMatchingNodes(json, path, asPathList)
        renderJSON(ret)
    }
}
