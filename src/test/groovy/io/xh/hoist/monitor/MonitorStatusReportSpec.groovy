/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.monitor

import io.xh.hoist.util.Utils
import spock.util.mop.ConfineMetaClassChanges
import spock.lang.Specification

import static io.xh.hoist.monitor.MonitorStatus.*

@ConfineMetaClassChanges([Utils])
class MonitorStatusReportSpec extends Specification {

    def setup() {
        Utils.metaClass.static.getAppName = { -> 'TestApp' }
    }

    private AggregateMonitorResult agg(String name, MonitorStatus status, String message = '', int minsAgo = 5) {
        Stub(AggregateMonitorResult) {
            getName() >> name
            getStatus() >> status
            getMessage() >> message
            getMinsInStatus() >> minsAgo.toString()
        }
    }

    private List<AggregateMonitorResult> aggs(int fails, int warns, int oks) {
        (0..<fails).collect { agg("f$it", FAIL) } +
            (0..<warns).collect { agg("w$it", WARN) } +
            (0..<oks).collect { agg("o$it", OK) }
    }

    def 'title summarizes counts for #fails fails, #warns warns, #oks oks'() {
        expect:
        new MonitorStatusReport(fails + warns + oks == 0 ? [] : aggs(fails, warns, oks)).title == title

        where:
        fails | warns | oks || title
        0     | 0     | 0   || 'TestApp: All clear'
        0     | 0     | 3   || 'TestApp: All clear | 3 OK'
        1     | 0     | 0   || 'TestApp: 1 Failures'
        2     | 1     | 4   || 'TestApp: 2 Failures | 1 Warnings | 4 OK'
        0     | 2     | 0   || 'TestApp: 2 Warnings'
    }

    def 'status is the most severe result status'() {
        expect:
        new MonitorStatusReport(statuses.collect { agg('x', it) }).status == expected

        where:
        statuses             | expected
        [OK, WARN]           | WARN
        []                   | OK
        [INACTIVE, UNKNOWN]  | UNKNOWN
    }

    def 'toHtml reports no alerts when nothing is at WARN or above'() {
        expect:
        new MonitorStatusReport([agg('a', OK)]).toHtml() == 'There are no alerting monitors for TestApp.'
    }

    def 'toHtml lists alerting monitors sorted by status then name'() {
        given:
        def report = new MonitorStatusReport([
            agg('zeta', FAIL, 'broken', 3),
            agg('beta', WARN, 'slow', 7),
            agg('alpha', WARN, '', 1),
            agg('fine', OK)
        ])

        expect:
        report.toHtml() == [
            '+ alpha | Minutes in [WARN]: 1',
            '+ beta | slow | Minutes in [WARN]: 7',
            '+ zeta | broken | Minutes in [FAIL]: 3'
        ].join('<br>')
    }
}
