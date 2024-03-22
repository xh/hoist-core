package io.xh.hoist.track

import grails.gorm.transactions.ReadOnly
import io.xh.hoist.BaseService;
import io.xh.hoist.config.ConfigService
import io.xh.hoist.data.filter.Filter
import io.xh.hoist.exception.DataNotAvailableException
import org.hibernate.Criteria
import org.hibernate.SessionFactory
import org.hibernate.criterion.Order
import org.hibernate.criterion.Restrictions

import javax.persistence.criteria.CriteriaBuilder
import java.time.LocalDate;

import static io.xh.hoist.util.DateTimeUtils.appEndOfDay
import static io.xh.hoist.util.DateTimeUtils.appStartOfDay
import static org.hibernate.criterion.Order.desc
import static org.hibernate.criterion.Restrictions.between

class TrackLogAdminService extends BaseService {
    ConfigService configService
    SessionFactory sessionFactory

    Boolean getEnabled() {
        return conf.enabled == true
    }

    @ReadOnly
    List<TrackLog> queryTrackLog(LocalDate startDay, LocalDate endDay, Filter filter, Integer maxRows = null) {
        if (!enabled) throw new DataNotAvailableException('TrackService not available.')

        def maxDefault = conf.maxRows.default as Integer,
            maxLimit = conf.maxRows.limit as Integer

        maxRows = [(maxRows ? maxRows : maxDefault), maxLimit].min()

        def session = sessionFactory.currentSession
        Criteria c = session.createCriteria(TrackLog)
        c.maxResults = maxRows
        c.addOrder(desc('dateCreated'))
        c.add(between('dateCreated', appStartOfDay(startDay), appEndOfDay(endDay)))
        if (filter) {
            c.add(filter.criterion)
        }
        c.list() as List<TrackLog>
    }

    @ReadOnly
    Map lookups() {[
        category: distinctVals('category'),
        browser: distinctVals('browser'),
        device: distinctVals('device'),
        username: distinctVals('username')
    ] }

    //------------------------
    // Implementation
    //------------------------
    private List distinctVals(String property) {
        TrackLog.createCriteria().list {
            projections { distinct(property) }
        }.sort()
    }

    private Map getConf() {
        configService.getMap('xhActivityTrackingConfig')
    }
}
