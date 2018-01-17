/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2017 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin

import io.xh.hoist.RestController
import io.xh.hoist.clienterror.ClientError
import io.xh.hoist.security.Access

@Access(['HOIST_ADMIN'])
class ClientErrorAdminController extends RestController {
    static restTarget = ClientError
    static trackChanges = false
}
