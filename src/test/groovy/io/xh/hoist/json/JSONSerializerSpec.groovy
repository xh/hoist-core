/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.json

import io.xh.hoist.AppEnvironment
import spock.lang.Specification

import java.time.Instant
import java.time.LocalDate

class JSONSerializerSpec extends Specification {

    static class Point implements JSONFormat {
        int x, y
        Object formatForJSON() { [px: x, py: y] }
    }

    def 'number #value is serialized as #expected'() {
        expect:
        JSONSerializer.serialize(value) == expected

        where:
        value                    | expected
        Double.NaN               | 'null'
        Double.POSITIVE_INFINITY | 'null'
        Double.NEGATIVE_INFINITY | 'null'
        Float.NaN                | 'null'
        Float.POSITIVE_INFINITY  | 'null'
        1.5d                     | '1.5'
        2.5f                     | '2.5'
        [a: Double.NaN]          | '{"a":null}'
        [1.0d, Double.NaN]       | '[1.0,null]'
    }

    def 'GStrings are serialized as strings, as values and as keys'() {
        given:
        def n = 1

        expect:
        JSONSerializer.serialize("x$n") == '"x1"'
        JSONSerializer.serialize([k: "v$n"]) == '{"k":"v1"}'
        JSONSerializer.serialize(["k$n": 'v']) == '{"k1":"v"}'
    }

    def 'dates and times use Hoist conventions'() {
        expect:
        JSONSerializer.serialize(LocalDate.of(2024, 1, 5)) == '"2024-01-05"'
        JSONSerializer.serialize(new Date(1234L)) == '1234'
        JSONSerializer.serialize(Instant.ofEpochMilli(1234L)) == '1234'
    }

    def 'JSONFormat objects are serialized via formatForJSON, including when nested'() {
        expect:
        JSONSerializer.serialize(new Point(x: 1, y: 2)) == '{"px":1,"py":2}'
        JSONSerializer.serialize([new Point(x: 1, y: 2)]) == '[{"px":1,"py":2}]'
        JSONSerializer.serialize([p: new Point(x: 3, y: 4)]) == '{"p":{"px":3,"py":4}}'
    }

    def 'enum implementing JSONFormat is serialized via its formatForJSON'() {
        expect:
        JSONSerializer.serialize(AppEnvironment.PRODUCTION) ==
            JSONSerializer.serialize(AppEnvironment.PRODUCTION.formatForJSON())
    }

    def 'serializePretty adds whitespace and round-trips'() {
        given:
        def data = [a: 1, b: [x: 'y', z: [1, 2]]]

        when:
        def pretty = JSONSerializer.serializePretty(data)

        then:
        pretty.contains('\n')
        pretty.contains('  ')
        JSONParser.parseObject(pretty) == data
    }

    def 'null is serialized as null'() {
        expect:
        JSONSerializer.serialize(null) == 'null'
    }
}
