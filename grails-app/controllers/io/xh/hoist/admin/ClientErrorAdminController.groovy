/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2020 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin

import io.xh.hoist.BaseController
import io.xh.hoist.clienterror.ClientError
import io.xh.hoist.security.Access

import static java.lang.Integer.parseInt

@Access(['HOIST_ADMIN'])
class ClientErrorAdminController extends BaseController {

    static int DEFAULT_MAX_ROWS = 25000

    def index() {
        def startDate = parseDate(params.startDate),
            endDate = parseDate(params.endDate),
            maxRows = params.maxRows ? parseInt(params.maxRows) : DEFAULT_MAX_ROWS

        def results = ClientError.findAll(max: maxRows, sort: 'dateCreated', order: 'desc') {
            if (startDate)          dateCreated >= startDate
            if (endDate)            dateCreated < endDate+1
            if (params.username)    username =~ "%$params.username%"
            if (params.error)       error =~ "%$params.error%"
        }

        renderJSON(results)
    }

    def lookups() {
        renderJSON([
            usernames: distinctVals('username'),
        ])
    }


    //------------------------
    // Implementation
    //------------------------
    private Date parseDate(String dateStr) {
        return dateStr ? Date.parse('yyyyMMdd', dateStr) : null
    }

    private List distinctVals(String property) {
        return ClientError.createCriteria().list {
            projections { distinct(property) }
        }.sort()
    }

}
