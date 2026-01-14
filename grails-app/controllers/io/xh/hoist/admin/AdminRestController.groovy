/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin

import io.xh.hoist.RestController
import io.xh.hoist.security.AccessRequiresRole

@AccessRequiresRole('HOIST_ADMIN_READER')
abstract class AdminRestController extends RestController {

    @AccessRequiresRole('HOIST_ADMIN')
    def create() {
        super.create()
    }

    @AccessRequiresRole('HOIST_ADMIN')
    def update() {
        super.update()
    }

    @AccessRequiresRole('HOIST_ADMIN')
    def bulkUpdate() {
        super.bulkUpdate()
    }

    @AccessRequiresRole('HOIST_ADMIN')
    def delete() {
        super.delete()
    }

    @AccessRequiresRole('HOIST_ADMIN')
    def bulkDelete() {
        super.bulkDelete()
    }
}
