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

import java.time.LocalDate

import static java.lang.Integer.parseInt
import static io.xh.hoist.util.DateTimeUtils.appStartOfDay
import static io.xh.hoist.util.DateTimeUtils.appEndOfDay
import static io.xh.hoist.util.DateTimeUtils.parseLocalDate

@Access(['HOIST_ADMIN'])
class TrackLogAdminController extends BaseController {

    static int DEFAULT_MAX_ROWS = 25000

    def trackService

    @ReadOnly
    def index() {
        def startDay = parseLocalDate(params.startDay),
            endDay = parseLocalDate(params.endDay),
            maxRows = params.maxRows ? parseInt(params.maxRows) : DEFAULT_MAX_ROWS

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
