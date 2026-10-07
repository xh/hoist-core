/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.monitor

import spock.lang.PendingFeature
import spock.lang.Specification

import static io.xh.hoist.monitor.MonitorStatus.*

class AggregateMonitorResultSpec extends Specification {

    static Monitor monitor(Map props = [:]) {
        def m = new Monitor()
        m.code = 'c'
        m.name = 'm'
        m.active = true
        props.each { k, v -> m[k] = v }
        m
    }

    static MonitorResult result(MonitorStatus status, String message = null, Monitor monitor = monitor()) {
        new MonitorResult(monitor: monitor, status: status, message: message)
    }

    static AggregateMonitorResult aggregate(MonitorStatus status, int s, int f, int w, Date changed = new Date(0)) {
        def ret = AggregateMonitorResult.newResults([result(status)], null)
        ret.cyclesAsSuccess = s
        ret.cyclesAsFail = f
        ret.cyclesAsWarn = w
        ret.lastStatusChanged = changed
        ret
    }

    def 'emptyResults is UNKNOWN for an active monitor and INACTIVE otherwise'() {
        expect:
        AggregateMonitorResult.emptyResults(monitor(active: true)).status == UNKNOWN
        AggregateMonitorResult.emptyResults(monitor(active: false)).status == INACTIVE
        AggregateMonitorResult.emptyResults(monitor()).results == null
        AggregateMonitorResult.emptyResults(monitor()).message == ''
    }

    def 'aggregate status is the most severe result status'() {
        expect:
        AggregateMonitorResult.newResults(statuses.collect { result(it) }, null).status == expected

        where:
        statuses              | expected
        [OK, WARN]            | WARN
        [OK, FAIL, WARN]      | FAIL
        [INACTIVE, OK]        | OK
        [UNKNOWN]             | UNKNOWN
        [INACTIVE]            | INACTIVE
    }

    def 'newResults #prevStatus #prevCounts to #newStatus gives #expected'() {
        given:
        def prev = aggregate(prevStatus, *prevCounts)

        when:
        def ret = AggregateMonitorResult.newResults([result(newStatus)], prev)

        then:
        [ret.cyclesAsSuccess, ret.cyclesAsFail, ret.cyclesAsWarn] == expected

        where:
        prevStatus | prevCounts | newStatus || expected
        OK         | [3, 0, 0]  | OK        || [4, 0, 0]
        OK         | [3, 0, 0]  | WARN      || [0, 0, 1]
        WARN       | [0, 0, 2]  | FAIL      || [0, 1, 2]
        FAIL       | [0, 2, 2]  | WARN      || [0, 0, 3]
        FAIL       | [0, 2, 1]  | OK        || [1, 0, 0]
        WARN       | [0, 0, 2]  | UNKNOWN   || [0, 0, 0]
        OK         | [3, 0, 0]  | INACTIVE  || [0, 0, 0]
    }

    def 'counts stay zero without a previous result'() {
        when:
        def ret = AggregateMonitorResult.newResults([result(FAIL)], null)

        then:
        ret.cyclesAsFail == 0
        ret.cyclesAsWarn == 0
        ret.cyclesAsSuccess == 0
    }

    def 'lastStatusChanged is carried over only when the status is unchanged'() {
        given:
        def changed = new Date(1000)
        def prev = aggregate(WARN, 0, 0, 1, changed)

        expect:
        AggregateMonitorResult.newResults([result(WARN)], prev).lastStatusChanged == changed
        AggregateMonitorResult.newResults([result(OK)], prev).lastStatusChanged.time > changed.time
    }

    def 'message is that of the first result matching the aggregate status'() {
        expect:
        AggregateMonitorResult.newResults(
            [result(OK, 'fine'), result(WARN, 'w1'), result(WARN, 'w2')], null
        ).message == 'w1'
    }

    def 'message is blank for OK, UNKNOWN and INACTIVE'() {
        expect:
        AggregateMonitorResult.newResults([result(status, 'msg')], null).message == ''

        where:
        status << [OK, UNKNOWN, INACTIVE]
    }

    def 'minsInStatus is truncated to whole minutes'() {
        given:
        def agg = aggregate(OK, 0, 0, 0, new Date(System.currentTimeMillis() - 5.5 * 60_000 as long))

        expect:
        agg.minsInStatus == '5'
    }

    @PendingFeature(reason = 'newResults dereferences results[0] and throws on an empty list rather than handling it')
    def 'newResults with an empty result list does not throw'() {
        when:
        AggregateMonitorResult.newResults([], null)

        then:
        noExceptionThrown()
    }

    def 'formatForJSON exposes the monitor identity, status and history'() {
        when:
        def json = AggregateMonitorResult.newResults([result(OK)], null).formatForJSON()

        then:
        json.keySet() == [
            'code', 'name', 'sortOrder', 'primaryOnly', 'metricUnit', 'status', 'results',
            'dateComputed', 'cyclesAsSuccess', 'cyclesAsFail', 'cyclesAsWarn', 'lastStatusChanged'
        ] as Set
        json.code == 'c'
        json.name == 'm'
    }
}
