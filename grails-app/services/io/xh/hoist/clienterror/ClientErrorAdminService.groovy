package io.xh.hoist.clienterror

import grails.gorm.transactions.ReadOnly
import io.xh.hoist.BaseService
import io.xh.hoist.data.filter.Filter
import io.xh.hoist.track.TrackLog
import org.hibernate.Criteria
import org.hibernate.SessionFactory

import java.time.LocalDate

import static io.xh.hoist.util.DateTimeUtils.appEndOfDay
import static io.xh.hoist.util.DateTimeUtils.appStartOfDay
import static org.hibernate.criterion.Order.desc
import static org.hibernate.criterion.Restrictions.between

class ClientErrorAdminService extends BaseService {
    SessionFactory sessionFactory

    @ReadOnly
    List<ClientError> queryClientError(LocalDate startDay, LocalDate endDay, Filter filter, int maxRows) {
        def session = sessionFactory.currentSession
        Criteria c = session.createCriteria(ClientError)
        c.maxResults = maxRows
        c.addOrder(desc('dateCreated'))
        c.add(between('dateCreated', appStartOfDay(startDay), appEndOfDay(endDay)))
        if (filter) {
            c.add(filter.criterion)
        }
        c.list() as List<ClientError>
    }

    @ReadOnly
    Map lookups() {[
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
}
