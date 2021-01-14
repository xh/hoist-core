/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2020 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin

import grails.gorm.transactions.ReadOnly
import io.xh.hoist.BaseController
import io.xh.hoist.clienterror.ClientError
import io.xh.hoist.security.Access

import java.time.LocalDate

import static io.xh.hoist.util.DateTimeUtils.appStartOfDay
import static io.xh.hoist.util.DateTimeUtils.appEndOfDay
import static java.lang.Integer.parseInt
import static java.time.format.DateTimeFormatter.BASIC_ISO_DATE

@Access(['HOIST_ADMIN'])
class ClientErrorAdminController extends BaseController {

    static int DEFAULT_MAX_ROWS = 25000

    @ReadOnly
    def index() {
        def startDay = parseDay(params.startDay),
            endDay = parseDay(params.endDay),
            maxRows = params.maxRows ? parseInt(params.maxRows) : DEFAULT_MAX_ROWS

        def results = ClientError.findAll(max: maxRows, sort: 'dateCreated', order: 'desc') {
            if (startDay)          dateCreated >= appStartOfDay(startDay)
            if (endDay)            dateCreated <= appEndOfDay(endDay)
            if (params.username)    username =~ "%$params.username%"
            if (params.error)       error =~ "%$params.error%"
        }

        renderJSON(results)
    }

    def lookups() {
        renderJSON([
            usernames: distinctVals('username')
        ])
    }


    //------------------------
    // Implementation
    //------------------------
    private LocalDate parseDay(String dateStr) {
        return dateStr ? LocalDate.parse(dateStr, BASIC_ISO_DATE) : null
    }

    private List distinctVals(String property) {
        return ClientError.createCriteria().list {
            projections { distinct(property) }
        }.sort()
    }

}
