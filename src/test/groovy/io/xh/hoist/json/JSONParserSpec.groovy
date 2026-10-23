/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.json

import tools.jackson.databind.exc.MismatchedInputException
import spock.lang.Specification

class JSONParserSpec extends Specification {

    def 'null or empty input parses to null'() {
        expect:
        JSONParser.parseObject(input as String) == null
        JSONParser.parseArray(input as String) == null
        JSONParser.parseObjectOrArray(input as String) == null

        where:
        input << [null, '']
    }

    def 'null streams parse to null'() {
        expect:
        JSONParser.parseObject((InputStream) null) == null
        JSONParser.parseArray((InputStream) null) == null
    }

    def 'streams are parsed'() {
        expect:
        JSONParser.parseObject(stream('{"a":1}')) == [a: 1]
        JSONParser.parseArray(stream('[1,2]')) == [1, 2]
    }

    def 'parseObjectOrArray trims and dispatches on first character'() {
        expect:
        JSONParser.parseObjectOrArray('  [1,2] ') == [1, 2]
        JSONParser.parseObjectOrArray(' {"a":1}') == [a: 1]
    }

    def 'JSON values map to expected java types'() {
        when:
        def m = JSONParser.parseObject(
            '{"i":1,"l":12345678901,"d":1.5,"b":true,"n":null,"big":123456789012345678901234,"s":"x"}'
        )

        then:
        m.i instanceof Integer
        m.l instanceof Long
        m.d instanceof Double
        m.b == true
        m.containsKey('n')
        m.n == null
        m.big instanceof BigInteger
        m.s == 'x'
    }

    def 'parsing the wrong top-level shape throws'() {
        when:
        JSONParser.parseObject('[1]')

        then:
        thrown(MismatchedInputException)

        when:
        JSONParser.parseArray('{}')

        then:
        thrown(MismatchedInputException)
    }

    def 'validate returns #expected for #input'() {
        expect:
        JSONParser.validate(input) == expected

        where:
        input               | expected
        null                | true
        '{}'                | true
        '[]'                | true
        '123'               | true
        '"s"'               | true
        '{"a":[1,{"b":2}]}' | true
        '{a:1}'             | false
        '{"a":1} x'         | false
        '{"a":1}{"b":2}'    | false
        '{"a":'             | false
    }

    def 'parseObject tolerates trailing tokens although validate rejects them'() {
        expect:
        JSONParser.parseObject('{"a":1} trailing') == [a: 1]
        !JSONParser.validate('{"a":1} trailing')
    }

    def 'serialize then parse round-trips nested structures'() {
        given:
        def data = [a: 1, b: [c: 'x', d: [1, 2, [e: true]]], f: null]

        expect:
        JSONParser.parseObject(JSONSerializer.serialize(data)) == data
    }

    private static InputStream stream(String s) {
        new ByteArrayInputStream(s.getBytes('UTF-8'))
    }
}
