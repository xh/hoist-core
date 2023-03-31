/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin

import grails.gorm.transactions.Transactional
import groovy.util.logging.Slf4j
import io.xh.hoist.RestController
import io.xh.hoist.security.Access

@Slf4j
@Transactional
@Access(['HOIST_ADMIN_READER'])
abstract class AdminRestController extends RestController{

    @Access(['HOIST_ADMIN'])
    def create() {
        super.create()
    }

    @Access(['HOIST_ADMIN'])
    def update() {
        super.update()
    }

    @Access(['HOIST_ADMIN'])
    def bulkUpdate() {
        super.bulkUpdate()
    }

    @Access(['HOIST_ADMIN'])
    def delete() {
        super.delete()
    }

    @Access(['HOIST_ADMIN'])
    def bulkDelete() {
        super.bulkDelete()
    }
}
