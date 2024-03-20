package io.xh.hoist.track

import io.xh.hoist.BaseService;
import io.xh.hoist.config.ConfigService
import io.xh.hoist.data.filter.Utils;
import static io.xh.hoist.util.DateTimeUtils.appEndOfDay
import static io.xh.hoist.util.DateTimeUtils.appStartOfDay
import static io.xh.hoist.util.DateTimeUtils.parseLocalDate
import static java.lang.Integer.parseInt;

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

        def startDay = parseLocalDate(query.startDay),
            endDay = parseLocalDate(query.endDay)

        def maxDefault = conf.maxRows.default as Integer,
            maxLimit = conf.maxRows.limit as Integer,
            maxRows = [(query.maxRows ? parseInt(query.maxRows) : maxDefault), maxLimit].min()

        def filters = Utils.parseFilter(query.filters)

        return TrackLog.findAll(
            'FROM TrackLog AS t WHERE ' +
                't.dateCreated >= :startDay AND t.dateCreated <= :endDay AND ' +
                Utils.createPredicateFromFilters(filters, 't'),
            [startDay: startDay? appStartOfDay(startDay): new Date(0), endDay: endDay? appEndOfDay(endDay): new Date()],
            [max: maxRows, sort: 'dateCreated', order: 'desc']
        )
    }
}
