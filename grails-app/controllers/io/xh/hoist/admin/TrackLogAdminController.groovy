/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin

import grails.gorm.transactions.ReadOnly
import io.xh.hoist.BaseController
import io.xh.hoist.security.Access
import io.xh.hoist.track.TrackLog
import io.xh.hoist.track.TrackLogAdminService


@Access(['HOIST_ADMIN_READER'])
class TrackLogAdminController extends BaseController {

    TrackLogAdminService trackLogAdminService

    @ReadOnly
    def index() {
        renderJSON(trackLogAdminService.queryTrackLog(parseRequestJSON()))
    }

    def lookups() {
        renderJSON([
            category: distinctVals('category'),
            browser: distinctVals('browser'),
            device: distinctVals('device'),
            username: distinctVals('username'),
        ])
    }

    //------------------------
    // Implementation
    //------------------------
    private List distinctVals(String property) {
        return TrackLog.createCriteria().list {
            projections { distinct(property) }
        }.sort()
    }

}
