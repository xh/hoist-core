/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2017 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin

import io.xh.hoist.RestController
import io.xh.hoist.clientexception.ClientException
import io.xh.hoist.security.Access

@Access(['HOIST_ADMIN'])
class ClientExceptionAdminController extends RestController {
    static restTarget = ClientException
    static trackChanges = false
}
