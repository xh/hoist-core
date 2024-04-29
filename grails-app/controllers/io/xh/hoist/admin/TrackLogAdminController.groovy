/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin

import io.xh.hoist.BaseController
import io.xh.hoist.data.filter.Filter
import io.xh.hoist.security.Access
import io.xh.hoist.track.TrackLogAdminService

import java.time.LocalDate

import static io.xh.hoist.util.DateTimeUtils.appDay
import static io.xh.hoist.util.DateTimeUtils.parseLocalDate


@Access(['HOIST_ADMIN_READER'])
class TrackLogAdminController extends BaseController {

    TrackLogAdminService trackLogAdminService

    def index() {
        def query = parseRequestJSON(),
            startDay = query.startDay ? parseLocalDate(query.startDay) : LocalDate.of(1970, 1, 1),
            endDay = query.endDay ? parseLocalDate(query.endDay) : appDay(),
            filter = Filter.parse(query.filters),
            maxRows = query.maxRows

        renderJSON(trackLogAdminService.queryTrackLog(filter, startDay, endDay, maxRows))
    }

    def lookups() {
        renderJSON(trackLogAdminService.lookups())
    }
}
