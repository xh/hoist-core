/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2022 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin

import grails.gorm.transactions.ReadOnly
import io.xh.hoist.BaseController
import io.xh.hoist.security.Access
import io.xh.hoist.track.TrackLog
import io.xh.hoist.track.TrackService

import static io.xh.hoist.util.DateTimeUtils.*
import static java.lang.Integer.parseInt

@Access(['HOIST_ADMIN_READER'])
class TrackLogAdminController extends BaseController {

    TrackService trackService

    @ReadOnly
    def index() {
        if (!trackService.enabled) {
            renderJSON([])
        }

        def startDay = parseLocalDate(params.startDay),
            endDay = parseLocalDate(params.endDay)

        // NOTE that querying + serializing large numbers of TrackLogs below requires a significant
        // allocation of memory. Be mindful if customizing maxRow-related configs above defaults!
        def conf = trackService.conf,
            maxDefault = conf.maxRows.default as Integer,
            maxLimit = conf.maxRows.limit as Integer,
            maxRows = [(params.maxRows ? parseInt(params.maxRows) : maxDefault), maxLimit].min()

        def results = TrackLog.findAll(max: maxRows, sort: 'dateCreated', order: 'desc') {
            if (startDay)           dateCreated >= appStartOfDay(startDay)
            if (endDay)             dateCreated <= appEndOfDay(endDay)
            if (params.category)    category =~ "%$params.category%"
            if (params.username)    username =~ "%$params.username%"
            if (params.browser)     browser =~ "%$params.browser%"
            if (params.device)      device =~ "%$params.device%"
            if (params.msg)         msg =~ "%$params.msg%"
        }

        renderJSON(results)
    }

    def lookups() {
        renderJSON([
            categories: distinctVals('category'),
            browsers: distinctVals('browser'),
            devices: distinctVals('device'),
            usernames: distinctVals('username'),
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
