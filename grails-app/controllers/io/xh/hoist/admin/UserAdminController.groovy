/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2018 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin

import io.xh.hoist.BaseController
import io.xh.hoist.security.Access
import io.xh.hoist.user.BaseUserService

@Access(['HOIST_ADMIN'])
class UserAdminController extends BaseController {

    BaseUserService userService

    def index(boolean activeOnly) {
        renderJSON(userService.list(activeOnly))
    }

}
