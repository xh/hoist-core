/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2022 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin

import grails.gorm.transactions.ReadOnly
import io.xh.hoist.BaseController
import io.xh.hoist.clienterror.ClientError
import io.xh.hoist.security.Access

import static io.xh.hoist.util.DateTimeUtils.appStartOfDay
import static io.xh.hoist.util.DateTimeUtils.appEndOfDay
import static io.xh.hoist.util.DateTimeUtils.parseLocalDate
import static java.lang.Integer.parseInt


@Access(['HOIST_ADMIN'])
class ClientErrorAdminController extends BaseController {

    static int DEFAULT_MAX_ROWS = 25000

    @ReadOnly
    def index() {
        def startDay = parseLocalDate(params.startDay),
            endDay = parseLocalDate(params.endDay),
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


    private List distinctVals(String property) {
        return ClientError.createCriteria().list {
            projections { distinct(property) }
        }.sort()
    }

}
