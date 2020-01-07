/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2019 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin

import grails.gorm.transactions.Transactional
import io.xh.hoist.BaseController
import io.xh.hoist.clienterror.ClientError
import io.xh.hoist.security.Access

@Access(['HOIST_ADMIN'])
class ClientErrorAdminController extends BaseController {

    @Transactional
    def index() {
        def startDate = parseDate(params.startDate),
            endDate = parseDate(params.endDate)

        def results = ClientError.findAll(max: 5000, sort: 'dateCreated', order: 'desc') {
            if (startDate)          dateCreated >= startDate
            if (endDate)            dateCreated < endDate+1
            if (params.username)    username =~ "%$params.username%"
            if (params.error)       error =~ "%$params.error%"
        }

        renderJSON(results)
    }

    //------------------------
    // Implementation
    //------------------------
    private static Date parseDate(String dateStr) {
        return dateStr ? Date.parse('yyyyMMdd', dateStr) : null
    }

}
