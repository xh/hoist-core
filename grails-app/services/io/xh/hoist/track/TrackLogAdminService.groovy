/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.track

import grails.gorm.transactions.ReadOnly
import io.xh.hoist.BaseService;
import io.xh.hoist.config.ConfigService
import io.xh.hoist.data.filter.Filter
import io.xh.hoist.exception.DataNotAvailableException
import org.hibernate.Criteria
import org.hibernate.SessionFactory
import java.time.LocalDate;

import static io.xh.hoist.util.DateTimeUtils.appEndOfDay
import static io.xh.hoist.util.DateTimeUtils.appStartOfDay
import static org.hibernate.criterion.Order.desc
import static org.hibernate.criterion.Restrictions.between

class TrackLogAdminService extends BaseService {
    ConfigService configService
    SessionFactory sessionFactory

    boolean getEnabled() {
        return conf.enabled
    }

    @ReadOnly
    List<TrackLog> queryTrackLog(Filter filter, LocalDate startDay = null, LocalDate endDay = null, Integer maxRows = null) {
        if (!enabled) throw new DataNotAvailableException('TrackService not available.')

        def maxDefault = conf.maxRows.defaultValue,
            maxLimit = conf.maxRows.limit
        maxRows = [(maxRows ? maxRows : maxDefault), maxLimit].min()

        withDebug(["Querying activity", [maxRows: maxRows]]) {
            def session = sessionFactory.currentSession
            Criteria c = session.createCriteria(TrackLog)
            c.maxResults = maxRows
            c.addOrder(desc('dateCreated'))
            if (startDay && endDay) {
                c.add(between('dateCreated', appStartOfDay(startDay), appEndOfDay(endDay)))
            }
            if (filter) {
                c.add(filter.criterion)
            }
            c.list() as List<TrackLog>
        }
    }

    @ReadOnly
    Map lookups() {
        withDebug("Loading lookups") {
            return [
                appVersion: distinctVals('appVersion'),
                browser: distinctVals('browser'),
                category: distinctVals('category'),
                device: distinctVals('device'),
                username: distinctVals('username')
            ]
        }
    }

    //------------------------
    // Implementation
    //------------------------
    private List distinctVals(String property) {
        TrackLog.createCriteria().list {
            projections { distinct(property) }
        }.sort()
    }

    private ActivityTrackingConfig getConf() {
        configService.getTypedConfig(ActivityTrackingConfig)
    }
}
