/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin

import grails.gorm.transactions.ReadOnly
import io.xh.hoist.BaseController
import io.xh.hoist.data.filter.Filter
import io.xh.hoist.data.filter.Utils
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

        def filters = null
        if (params.filters) {
            filters = Utils.parseFilter(JSONParser.parseObject(params.filters))
        }

        def results = TrackLog.findAll(
            'FROM TrackLog AS t WHERE ' +
            't.dateCreated >= :startDay AND t.dateCreated <= :endDay AND ' +
            '(' + trackService.createPredicateFromFilters(filters, 't') + ')',
            [startDay: startDay? appStartOfDay(startDay): new Date(0), endDay: endDay? appEndOfDay(endDay): new Date()],
            [max: maxRows, sort: 'dateCreated', order: 'desc']
        )

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
