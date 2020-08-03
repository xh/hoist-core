/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2020 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin

import io.xh.hoist.BaseController
import io.xh.hoist.security.Access
import io.xh.hoist.track.TrackLog

import static java.lang.Integer.parseInt

@Access(['HOIST_ADMIN'])
class TrackLogAdminController extends BaseController {

    static int DEFAULT_MAX_ROWS = 25000

    def trackService

    def index() {
        def startDate = parseDate(params.startDate),
            endDate = parseDate(params.endDate),
            maxRows = params.maxRows ? parseInt(params.maxRows) : DEFAULT_MAX_ROWS

        def results = TrackLog.findAll(max: maxRows, sort: 'dateCreated', order: 'desc') {
            if (startDate)          dateCreated >= startDate
            if (endDate)            dateCreated < endDate+1
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

    def dailyVisitors() {
        def startDate = parseDate(params.startDate) ?: new Date(),
            endDate = parseDate(params.endDate) ?: new Date(),
            username = params.username ? params.username : null

        renderJSON(
            trackService.getUniqueVisitsByDay(startDate, endDate+1, username)
        )
    }

    
    //------------------------
    // Implementation
    //------------------------
    private Date parseDate(String dateStr) {
        return dateStr ? Date.parse('yyyyMMdd', dateStr) : null
    }

    private List distinctVals(String property) {
        return TrackLog.createCriteria().list {
            projections { distinct(property) }
        }.sort()
    }

}