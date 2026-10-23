/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.monitor

import spock.lang.Specification

class MonitorResultSpec extends Specification {

    private MonitorResult resultWithParams(String params) {
        def m = new Monitor()
        m.code = 'myCode'
        m.params = params
        new MonitorResult(monitor: m)
    }

    def 'params parses the monitor JSON, defaulting to empty'() {
        expect:
        resultWithParams(json).params == expected

        where:
        json                      | expected
        null                      | [:]
        ''                        | [:]
        '{"lookbackMinutes":30}'  | [lookbackMinutes: 30]
    }

    def 'getParam falls back to the default only when the key is absent'() {
        given:
        def r = resultWithParams('{"a":1,"n":null}')

        expect:
        r.getParam('a', 9) == 1
        r.getParam('missing', 9) == 9
        r.getParam('missing') == null
        r.getParam('n', 9) == null
    }

    def 'getRequiredParam throws when missing'() {
        given:
        def r = resultWithParams('{"a":1}')

        expect:
        r.getRequiredParam('a') == 1

        when:
        r.getRequiredParam('b')

        then:
        def ex = thrown(RuntimeException)
        ex.message == 'Missing required parameter b'
    }

    def 'prependMessage handles #existing'() {
        given:
        def r = resultWithParams(null)
        r.message = existing

        when:
        r.prependMessage('P')

        then:
        r.message == expected

        where:
        existing | expected
        null     | 'P'
        ''       | 'P'
        'M'      | 'P \n\nM'
    }

    def 'prependMessage can be applied repeatedly'() {
        given:
        def r = resultWithParams(null)
        r.message = 'M'

        when:
        r.prependMessage('A')
        r.prependMessage('B')

        then:
        r.message == 'B \n\nA \n\nM'
    }

    def 'status defaults to UNKNOWN and code delegates to the monitor'() {
        given:
        def r = resultWithParams(null)

        expect:
        r.status == MonitorStatus.UNKNOWN
        r.code == 'myCode'
    }

    def 'formatForJSON excludes the monitor'() {
        expect:
        resultWithParams(null).formatForJSON().keySet() ==
            ['instance', 'primary', 'status', 'metric', 'message', 'elapsed', 'date', 'exception'] as Set
    }
}
