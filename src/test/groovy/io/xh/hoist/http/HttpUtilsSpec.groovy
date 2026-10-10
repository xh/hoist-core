/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.http

import jakarta.servlet.http.HttpServletResponse
import spock.lang.Specification

class HttpUtilsSpec extends Specification {

    def 'parseHostPort splits #input into #expected'() {
        expect:
        HttpUtils.parseHostPort(input) == expected

        where:
        input                 | expected
        'host'                | ['host', null]
        'host:8080'           | ['host', 8080]
        'localhost:0'         | ['localhost', 0]
        'a.b.example.com:443' | ['a.b.example.com', 443]
    }

    def 'parseHostPort returns an Integer port'() {
        expect:
        HttpUtils.parseHostPort('host:8080')[1] instanceof Integer
    }

    def 'parseHostPort rejects a non-numeric port'() {
        when:
        HttpUtils.parseHostPort('host:abc')

        then:
        thrown(NumberFormatException)
    }

    def 'parseHostPort fails on null input'() {
        when:
        HttpUtils.parseHostPort(null)

        then:
        thrown(NullPointerException)
    }

    def 'parseHostPort silently ignores trailing segments'() {
        expect: 'documents current behavior - extra colon-delimited segments are dropped'
        HttpUtils.parseHostPort('a:1:2') == ['a', 1]
    }

    def 'setResponseCache with #minutes minutes sets Cache-Control #header'() {
        given:
        def resp = Mock(HttpServletResponse)

        when:
        HttpUtils.setResponseCache(resp, minutes)

        then:
        1 * resp.setHeader('Cache-Control', header)

        where:
        minutes | header
        0       | 'no-cache'
        -5      | 'no-cache'
        5       | 'public, max-age=300'
        60      | 'public, max-age=3600'
    }

    def 'setResponseCache sets Expires relative to now for #minutes minutes'() {
        given:
        def resp = Mock(HttpServletResponse)
        long before = System.currentTimeMillis()
        long offset = Math.max(0, minutes) * 60_000L
        Long expires = null

        when:
        HttpUtils.setResponseCache(resp, minutes)
        long after = System.currentTimeMillis()

        then:
        1 * resp.setDateHeader('Expires', _) >> { String name, long value -> expires = value }
        expires >= before + offset
        expires <= after + offset

        where:
        minutes << [0, -1, 5]
    }
}
