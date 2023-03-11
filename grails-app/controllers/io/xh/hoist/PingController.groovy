/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2022 Extremely Heavy Industries Inc.
 */
package io.xh.hoist

import groovy.transform.CompileStatic
import io.xh.hoist.security.AccessAll
import io.xh.hoist.util.Utils


/**
 * A deliberately trivial endpoint. Provides a baseline check that a Hoist app server is up,
 * running, and reachable without any additional expectations or overhead.
 */
@AccessAll
@CompileStatic
class PingController extends BaseController {

    def index() {
        renderJSON([
            appCode: Utils.appCode,
            appName: Utils.appName,
            timestamp: System.currentTimeMillis(),
            success: true
        ])
    }

}
