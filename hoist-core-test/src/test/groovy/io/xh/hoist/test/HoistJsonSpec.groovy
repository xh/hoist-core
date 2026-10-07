/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.test

import io.xh.hoist.json.JSONFormat
import spock.lang.Specification

import java.time.LocalDate

class HoistJsonSpec extends Specification {

    static class Widget implements JSONFormat {
        String name = 'w'
        LocalDate day = LocalDate.of(2026, 1, 2)
        Map formatForJSON() { [name: name, day: day, tags: ['a', 'b'], ratio: 1.5d] }
    }

    def 'serialize and parse use hoist formats'() {
        expect:
        HoistJson.serialize([a: 1]) == '{"a":1}'
        HoistJson.parse('{"a":1}') == [a: 1]
        HoistJson.parse('[1,2]') == [1, 2]
    }

    def 'round trip of a JSONFormat object shows the wire representation'() {
        when:
        def json = HoistJson.roundTripObject(new Widget())

        then:
        json == [name: 'w', day: '2026-01-02', tags: ['a', 'b'], ratio: 1.5d]
    }

    def 'round trip of collections'() {
        expect:
        HoistJson.roundTripArray([new Widget(), new Widget(name: 'x')])*.name == ['w', 'x']
        HoistJson.roundTrip([k: 'v']) == [k: 'v']
        HoistJson.roundTrip(null) == null
    }

    def 'GStrings serialize as strings'() {
        given:
        def x = 'there'

        expect:
        HoistJson.roundTrip(["hi $x"]) == ['hi there']
    }

    def 'throwables serialize, with the exception class name'() {
        when:
        def json = HoistJson.roundTripObject(new io.xh.hoist.exception.RoutineRuntimeException('oops'))

        then:
        json.message == 'oops'
        json.name == 'RoutineRuntimeException'
    }
}
