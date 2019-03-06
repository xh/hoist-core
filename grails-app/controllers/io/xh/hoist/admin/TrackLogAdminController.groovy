/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2019 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin

import io.xh.hoist.BaseController
import io.xh.hoist.security.Access
import io.xh.hoist.track.TrackLog

@Access(['HOIST_ADMIN'])
class TrackLogAdminController extends BaseController {

    def trackService

    def index() {
        def startDate = parseDate(params.startDate),
            endDate = parseDate(params.endDate)

        def results = TrackLog.findAll(max: 5000, sort: 'dateCreated', order: 'desc') {
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
    private static Date parseDate(String dateStr) {
        return dateStr ? Date.parse('yyyyMMdd', dateStr) : null
    }

}