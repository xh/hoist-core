/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.admin

import spock.lang.Specification

class ClusterObjectsReportSpec extends Specification {

    private static Map obj(String name, String instance, Map stats, List comparable = ['size']) {
        [name: name, instanceName: instance, adminStats: stats, comparableAdminStats: comparable]
    }

    private static Map breaks(List<Map> info) {
        new ClusterObjectsReport(info, 1L, 2L).breaks
    }

    def 'no info gives no breaks'() {
        expect:
        breaks([]).isEmpty()
    }

    def 'a single instance per object gives no breaks'() {
        expect:
        breaks([obj('a', 'i1', [size: 1]), obj('b', 'i1', [size: 2])]).isEmpty()
    }

    def 'matching comparable stats give no break'() {
        expect:
        breaks([obj('a', 'i1', [size: 3]), obj('a', 'i2', [size: 3])]).isEmpty()
    }

    def 'differing comparable stats record the pair in both orders'() {
        expect:
        breaks([obj('a', 'i1', [size: 3]), obj('a', 'i2', [size: 4])]) == [a: [['i1', 'i2'], ['i2', 'i1']]]
    }

    def 'differing comparable stat lists are a break even when stats match'() {
        expect:
        breaks([obj('a', 'i1', [size: 3], ['size']), obj('a', 'i2', [size: 3], [])]).keySet() == ['a'] as Set
    }

    def 'non-comparable stats are ignored'() {
        expect:
        breaks([
            obj('a', 'i1', [size: 3, lastAccessTime: 1]),
            obj('a', 'i2', [size: 3, lastAccessTime: 2])
        ]).isEmpty()
    }

    def 'an outlier among three instances is paired with each of the others only'() {
        when:
        def result = breaks([
            obj('a', 'i1', [size: 3]), obj('a', 'i2', [size: 3]), obj('a', 'i3', [size: 9])
        ])

        then:
        result.a.size() == 4
        result.a.every { 'i3' in it }
        !result.a.contains(['i1', 'i2'])
        !result.a.contains(['i2', 'i1'])
    }

    def 'only objects that differ are keyed in breaks'() {
        when:
        def result = breaks([
            obj('ok', 'i1', [size: 1]), obj('ok', 'i2', [size: 1]),
            obj('bad', 'i1', [size: 1]), obj('bad', 'i2', [size: 2])
        ])

        then:
        result.keySet() == ['bad'] as Set
    }

    def 'null comparable stats on both sides give no break'() {
        expect:
        breaks([obj('a', 'i1', [:], null), obj('a', 'i2', [:], null)]).isEmpty()
    }

    def 'formatForJSON exposes info, breaks and timestamps'() {
        given:
        def info = [obj('a', 'i1', [size: 1])]

        expect:
        new ClusterObjectsReport(info, 10L, 20L).formatForJSON() ==
            [info: info, breaks: [:], startTimestamp: 10L, endTimestamp: 20L]
    }
}
