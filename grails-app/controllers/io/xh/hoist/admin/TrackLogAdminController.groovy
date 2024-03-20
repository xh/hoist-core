/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin

import grails.gorm.transactions.ReadOnly
import io.xh.hoist.BaseController
import io.xh.hoist.json.JSONParser
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

        def category, browser, device, username
        def categoryOp, browserOp, deviceOp, usernameOp
        if (params.filters) {
            def filters = JSONParser.parseArray(params.filters)
            filters.each {filter ->
                if (filter) {
                    switch (filter.get('field')) {
                        case 'category': category = filter.get('value'); categoryOp = filter.get('op'); break
                        case 'browser': browser = filter.get('value'); browserOp = filter.get('op'); break
                        case 'device': device = filter.get('value'); deviceOp = filter.get('op'); break
                        case 'username': username = filter.get('value'); usernameOp = filter.get('op'); break
                    }
                }
            }
        }

        def results = TrackLog.findAll(max: maxRows, sort: 'dateCreated', order: 'desc') {
            if (startDay)           dateCreated >= appStartOfDay(startDay)
            if (endDay)             dateCreated <= appEndOfDay(endDay)
            if (category && categoryOp.equals('='))           category == category
            if (category && categoryOp.equals('!='))          category != category
            if (category && categoryOp.equals('like'))        category =~ category
            if (username)           username =~ username
            if (browser)            browser =~ browser
            if (device)             device =~ device
        }

        renderJSON(results)
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
