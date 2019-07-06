/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2019 Extremely Heavy Industries Inc.
 */
package io.xh.hoist

import groovy.transform.CompileStatic
import io.xh.hoist.security.AccessAll
import io.xh.hoist.util.Utils

@AccessAll
@CompileStatic
class PingController extends BaseController {

    def index() {
        renderJSON([
                appCode  : Utils.appCode,
                appName  : Utils.appName,
                timestamp: System.currentTimeMillis(),
                success  : true
        ])
    }
}
