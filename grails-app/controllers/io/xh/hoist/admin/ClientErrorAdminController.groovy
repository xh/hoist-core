/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2025 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin

import grails.gorm.transactions.ReadOnly
import io.xh.hoist.BaseController
import io.xh.hoist.clienterror.ClientErrorAdminService
import io.xh.hoist.security.Access

import static io.xh.hoist.util.DateTimeUtils.appDay
import static io.xh.hoist.util.DateTimeUtils.parseLocalDate
import io.xh.hoist.data.filter.Filter
import java.time.LocalDate



@Access(['HOIST_ADMIN_READER'])
class ClientErrorAdminController extends BaseController {

    ClientErrorAdminService clientErrorAdminService
    static int DEFAULT_MAX_ROWS = 25000

    @ReadOnly
    def index() {
        def query = parseRequestJSON(),
        startDay = query.startDay? parseLocalDate(query.startDay) : LocalDate.of(1970, 1, 1),
        endDay = query.endDay? parseLocalDate(query.endDay) : appDay(),
        filter = Filter.parse(query.filters),
        maxRows = query.maxRows ?: DEFAULT_MAX_ROWS

        renderJSON(clientErrorAdminService.queryClientError(startDay, endDay, filter, maxRows))
    }

    def lookups() {
        renderJSON(clientErrorAdminService.lookups())
    }
}
