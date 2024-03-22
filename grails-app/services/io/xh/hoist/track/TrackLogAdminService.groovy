/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2024 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.track

import io.xh.hoist.BaseService;
import io.xh.hoist.config.ConfigService
import io.xh.hoist.data.filter.FilterUtils
import io.xh.hoist.util.DateTimeUtils

import java.time.LocalDate;

import static io.xh.hoist.util.DateTimeUtils.appEndOfDay
import static io.xh.hoist.util.DateTimeUtils.appStartOfDay
import static io.xh.hoist.util.DateTimeUtils.parseLocalDate

class TrackLogAdminService extends BaseService {
    ConfigService configService

    Boolean getEnabled() {
        return conf.enabled == true
    }

    Map getConf() {
        return configService.getMap('xhActivityTrackingConfig')
    }

    List<TrackLog> queryTrackLog(Map query) {
        if (!enabled) {
            return []
        }

        def startDay = query.startDay? parseLocalDate(query.startDay): LocalDate.of(1970, 1, 1),
            endDay = query.endDay? parseLocalDate(query.endDay): DateTimeUtils.appDay()

        def maxDefault = conf.maxRows.default as Integer,
            maxLimit = conf.maxRows.limit as Integer,
            maxRows = [(query.maxRows ? query.maxRows : maxDefault), maxLimit].min()

        def filters = FilterUtils.parseFilter(query.filters)

        return TrackLog.findAll(
            'FROM TrackLog AS t WHERE ' +
                't.dateCreated >= :startDay AND t.dateCreated <= :endDay AND ' +
                FilterUtils.createPredicateFromFilters(filters, 't'),
            [startDay: appStartOfDay(startDay), endDay: appEndOfDay(endDay)],
            [max: maxRows, sort: 'dateCreated', order: 'desc']
        )
    }

    Map lookups() {
        return [
            category: distinctVals('category'),
            browser: distinctVals('browser'),
            device: distinctVals('device'),
            username: distinctVals('username'),
        ]
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
