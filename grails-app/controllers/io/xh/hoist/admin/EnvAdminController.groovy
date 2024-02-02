/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.admin

import io.xh.hoist.BaseController
import io.xh.hoist.security.Access

@Access(['HOIST_ADMIN_READER'])
class EnvAdminController extends BaseController {

    def index() {
        renderJSON([
            environment: System.getenv().collectEntries {
                [it.key, it.key.endsWithIgnoreCase('password') ? '*****' : it.value]
            },
            properties: System.getProperties()
        ])
    }

}
