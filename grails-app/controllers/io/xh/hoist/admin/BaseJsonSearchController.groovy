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

class BaseJsonSearchController extends BaseController {

    private Configuration matchSearchConf = Configuration.builder()
        .options(
            Option.SUPPRESS_EXCEPTIONS,
            Option.ALWAYS_RETURN_LIST
        ).build()

    private Configuration nodeSearchConf = Configuration.builder()
        .options(
            Option.AS_PATH_LIST,
            Option.ALWAYS_RETURN_LIST
        ).build()

    def getMatchingNodes(String json, String path, boolean asPathList) {
        def ret = JsonPath.using(nodeSearchConf).parse(json).read(path)
        renderJSON(ret)
    }

    protected hasPathMatch(String json, String path) {
        def result = JsonPath.using(matchSearchConf).parse(json).read(path)
        return result.size() > 0
    }
}
