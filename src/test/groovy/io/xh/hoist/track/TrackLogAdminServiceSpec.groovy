/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.track

import grails.test.hibernate.HibernateSpec
import grails.testing.services.ServiceUnitTest
import io.xh.hoist.json.JSONSerializer
import io.xh.hoist.test.HoistUnitTest

import static io.xh.hoist.util.DateTimeUtils.DAYS
import static io.xh.hoist.util.DateTimeUtils.HOURS

/**
 * Runs against Hibernate over in-memory H2, as the usage summary's grouped projections are not
 * supported by the `DataTest` in-memory datastore.
 */
class TrackLogAdminServiceSpec extends HibernateSpec implements ServiceUnitTest<TrackLogAdminService>, HoistUnitTest {

    List<Class> getDomainClasses() { [TrackLog] }

    def setup() {
        testConfigService.registerTypedConfig('xhActivityTrackingConfig', ActivityTrackingConfig)
    }

    def 'usage summary aggregates counts within the window'() {
        given:
        def now = System.currentTimeMillis()
        log('alice', 'App', 'Loaded app', '2.0', 'Chrome', now - HOURS)
        log('alice', 'Grid', 'Exported', '2.0', 'Chrome', now - 2 * HOURS)
        log('bob', 'App', 'Loaded app', '1.9', 'Firefox', now - 3 * DAYS)
        log('bob', 'Client Error', 'Boom', '1.9', 'Firefox', now - 3 * DAYS)
        log('carol', 'Feedback', 'Nice', '2.0', 'Chrome', now - 10 * DAYS)
        log('dave', 'App', 'Loaded app', '1.0', 'Chrome', now - 40 * DAYS)  // outside window

        when:
        def usage = service.getUsageSummary(30)

        then:
        usage.windowDays == 30
        usage.entries == 5
        usage.distinctUsers == 3
        usage.distinctUsersLast7d == 2
        usage.distinctUsersLast1d == 1
        usage.appLoads == 2
        usage.clientErrors == 1
        usage.feedback == 1
        usage.lastActivity.time == now - HOURS
        usage.byClientVersion.collect { [it.value, it.distinctUsers, it.entries] } == [
            ['2.0', 2, 3], ['1.9', 1, 2]
        ]
        usage.byBrowser*.value == ['Chrome', 'Firefox']
    }

    def 'client build summary reports libraries from recent loads, tolerating clients that do not report them'() {
        given:
        def now = System.currentTimeMillis()
        def v2Libs = [hoistReact: '76.3.1', react: '19.2.0']
        load('alice', 'app', '2.0', now - 3 * HOURS, [clientLibraries: v2Libs])
        load('bob', 'app', '2.0', now - 2 * HOURS, [clientLibraries: v2Libs])
        load('bob', 'app', '2.0', now - HOURS, null)                      // data dropped as over-length
        load('carol', 'app', '1.9', now - 5 * DAYS, [timings: [total: 1]])  // pre-reporting hoist-react
        load('dave', 'admin', '2.0', now - 4 * HOURS, [clientLibraries: 'garbage'])
        load('erin', null, null, now - 6 * DAYS, null)
        load('frank', 'app', '1.0', now - 40 * DAYS, [clientLibraries: [hoistReact: '70.0.0']])  // outside window
        log('alice', 'Grid', 'Exported', '2.0', 'Chrome', now - HOURS)    // not a load

        when:
        def builds = service.getClientBuildSummary(30)

        then: 'ordered by most recent load, with libraries where reported'
        builds.collect { [it.clientAppCode, it.appVersion, it.distinctUsers, it.loads, it.libraries] } == [
            ['app', '2.0', 2, 3, v2Libs],
            ['admin', '2.0', 1, 1, null],
            ['app', '1.9', 1, 1, null],
            [null, null, 1, 1, null]
        ]
        builds[0].lastLoad.time == now - HOURS

        and: 'limited to maxBuilds'
        service.getClientBuildSummary(30, 2).size() == 2
    }

    private static void load(String username, String clientAppCode, String appVersion, long time, Map data) {
        def tl = new TrackLog(
            username: username,
            category: 'App',
            msg: "Loaded ${clientAppCode}",
            appVersion: appVersion,
            clientAppCode: clientAppCode,
            data: data != null ? JSONSerializer.serialize(data) : null,
            severity: 'INFO'
        )
        tl.dateCreated = new Date(time)
        tl.save(failOnError: true)
    }

    private static void log(String username, String category, String msg, String appVersion, String browser, long time) {
        def tl = new TrackLog(
            username: username,
            category: category,
            msg: msg,
            appVersion: appVersion,
            browser: browser,
            device: 'Mac',
            clientAppCode: 'app',
            severity: 'INFO'
        )
        tl.dateCreated = new Date(time)  // not bindable via the map constructor
        tl.save(failOnError: true)
    }
}
