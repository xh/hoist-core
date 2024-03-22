/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2024 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin

import grails.gorm.transactions.ReadOnly
import io.xh.hoist.BaseController
import io.xh.hoist.security.Access
import io.xh.hoist.track.TrackLogAdminService


@Access(['HOIST_ADMIN_READER'])
class TrackLogAdminController extends BaseController {

    TrackLogAdminService trackLogAdminService

    @ReadOnly
    def index() {
        renderJSON(trackLogAdminService.queryTrackLog(parseRequestJSON()))
    }

    def lookups() {
        renderJSON(trackLogAdminService.lookups())
    }
}
