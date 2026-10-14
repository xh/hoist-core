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

import static io.xh.hoist.util.DateTimeUtils.DAYS
import static io.xh.hoist.util.DateTimeUtils.appEndOfDay
import static io.xh.hoist.util.DateTimeUtils.appStartOfDay
import static org.hibernate.criterion.Order.desc
import static org.hibernate.criterion.Restrictions.between

class TrackLogAdminService extends BaseService {

    // App load entries, as tracked by hoist-react on each client app startup.
    private static final String LOAD_CATEGORY = 'App'
    private static final String LOAD_MSG_PATTERN = 'Loaded%'

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

    /**
     * Aggregate summary of tracked activity over a trailing window of days, as reported in the
     * `usage` section of the inventory report built by `EnvironmentService`.
     *
     * Reports counts only - never usernames or other per-user detail. Breakdowns by client app,
     * client version, browser, and device list distinct users, entry count, and last activity for
     * each value, ordered by distinct users. The client version breakdown in particular surfaces
     * users still running a stale client build.
     */
    @ReadOnly
    Map getUsageSummary(int days) {
        def now = new Date(),
            since = new Date(now.time - days * DAYS),
            totals = TrackLog.createCriteria().get {
                ge('dateCreated', since)
                projections {
                    rowCount()
                    countDistinct('username')
                    max('dateCreated')
                }
            } as List

        return [
            trackingEnabled     : enabled,
            windowDays          : days,
            since               : since,
            entries             : totals[0] ?: 0,
            distinctUsers       : totals[1] ?: 0,
            distinctUsersLast7d : countDistinctUsers(new Date(now.time - 7 * DAYS)),
            distinctUsersLast1d : countDistinctUsers(new Date(now.time - DAYS)),
            lastActivity        : totals[2],
            appLoads            : countEntries(since, LOAD_CATEGORY, LOAD_MSG_PATTERN),
            clientErrors        : countEntries(since, 'Client Error'),
            feedback            : countEntries(since, 'Feedback'),
            byClientApp         : breakdown(since, 'clientAppCode'),
            byClientVersion     : breakdown(since, 'appVersion'),
            byBrowser           : breakdown(since, 'browser'),
            byDevice            : breakdown(since, 'device')
        ]
    }

    /**
     * Client builds observed loading over a trailing window of days, as reported in the `client`
     * section of the inventory report built by `EnvironmentService`.
     *
     * Groups app load entries by client app and client version, most recently loaded first, with
     * distinct users, load count, and last load for each. Each also carries the `libraries` -
     * versions of key client-side libraries such as hoist-react - reported by the most recent
     * loads of that build, via a `clientLibraries` map in the load entry's data. Builds from
     * hoist-react versions that do not yet report this map have null `libraries`.
     */
    @ReadOnly
    List<Map> getClientBuildSummary(int days, int maxBuilds = 25) {
        def since = new Date(System.currentTimeMillis() - days * DAYS),
            rows = TrackLog.createCriteria().list {
                ge('dateCreated', since)
                eq('category', LOAD_CATEGORY)
                like('msg', LOAD_MSG_PATTERN)
                projections {
                    groupProperty('clientAppCode')
                    groupProperty('appVersion')
                    countDistinct('username')
                    rowCount()
                    max('dateCreated')
                }
            } as List<List>

        return rows
            .sort { -(it[4] as Date).time }
            .take(maxBuilds)
            .collect { row ->
                def (clientAppCode, appVersion) = [row[0] as String, row[1] as String]
                [
                    clientAppCode: clientAppCode,
                    appVersion   : appVersion,
                    distinctUsers: row[2],
                    loads        : row[3],
                    lastLoad     : row[4],
                    libraries    : findClientLibraries(since, clientAppCode, appVersion)
                ]
            }
    }

    //------------------------
    // Implementation
    //------------------------
    // Read from the latest few loads, as a load's data is dropped by the client if over-length.
    private Map findClientLibraries(Date since, String clientAppCode, String appVersion) {
        def loads = TrackLog.createCriteria().list(max: 5) {
            ge('dateCreated', since)
            eq('category', LOAD_CATEGORY)
            like('msg', LOAD_MSG_PATTERN)
            clientAppCode != null ? eq('clientAppCode', clientAppCode) : isNull('clientAppCode')
            appVersion != null ? eq('appVersion', appVersion) : isNull('appVersion')
            order('dateCreated', 'desc')
        } as List<TrackLog>

        return loads.findResult {
            def libs = it.dataAsObject?.clientLibraries
            libs instanceof Map ? libs : null
        } as Map
    }

    private Long countDistinctUsers(Date since) {
        TrackLog.createCriteria().get {
            ge('dateCreated', since)
            projections { countDistinct('username') }
        } as Long ?: 0
    }

    private Long countEntries(Date since, String category, String msgPattern = null) {
        TrackLog.createCriteria().get {
            ge('dateCreated', since)
            eq('category', category)
            if (msgPattern) like('msg', msgPattern)
            projections { rowCount() }
        } as Long ?: 0
    }

    private List<Map> breakdown(Date since, String property) {
        def rows = TrackLog.createCriteria().list {
            ge('dateCreated', since)
            projections {
                groupProperty(property)
                countDistinct('username')
                rowCount()
                max('dateCreated')
            }
        } as List<List>

        return rows
            .collect { [value: it[0], distinctUsers: it[1], entries: it[2], lastActivity: it[3]] }
            .sort { -(it.distinctUsers as Long) }
    }

    private List distinctVals(String property) {
        TrackLog.createCriteria().list {
            projections { distinct(property) }
        }.sort()
    }

    private ActivityTrackingConfig getConf() {
        configService.getObject(ActivityTrackingConfig)
    }
}
