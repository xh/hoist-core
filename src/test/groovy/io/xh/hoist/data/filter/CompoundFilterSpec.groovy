/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.data.filter

import io.xh.hoist.json.JSONParser
import io.xh.hoist.json.JSONSerializer
import io.xh.hoist.test.HoistSpec
import org.hibernate.criterion.Conjunction
import org.hibernate.criterion.Disjunction
import spock.lang.Unroll

class CompoundFilterSpec extends HoistSpec {

    static Map a1 = [field: 'a', op: '=', value: 1]
    static Map b2 = [field: 'b', op: '=', value: 2]

    @Unroll
    def 'op #input normalizes to #expected'() {
        expect:
        new CompoundFilter([a1], input).op == expected

        where:
        input | expected
        null  | 'AND'
        ''    | 'AND'
        'or'  | 'OR'
        'And' | 'AND'
        'AND' | 'AND'
    }

    def 'invalid op throws'() {
        when:
        new CompoundFilter([a1], 'XOR')

        then:
        thrown(RuntimeException)
    }

    def 'children are parsed and nulls dropped'() {
        when:
        def filter = new CompoundFilter([null, a1], 'AND')

        then:
        filter.filters.size() == 1
        filter.filters[0] instanceof FieldFilter
    }

    @Unroll
    def '#op over a=#a b=#b -> #expected'() {
        given:
        def fn = new CompoundFilter([a1, b2], op).testFn

        expect:
        fn.call([a: a, b: b]) == expected

        where:
        op    | a | b | expected
        'AND' | 1 | 2 | true
        'AND' | 1 | 0 | false
        'AND' | 0 | 2 | false
        'AND' | 0 | 0 | false
        'OR'  | 1 | 2 | true
        'OR'  | 1 | 0 | true
        'OR'  | 0 | 2 | true
        'OR'  | 0 | 0 | false
    }

    @Unroll
    def 'empty #op filter passes everything'() {
        expect:
        new CompoundFilter([], op).testFn.call([a: 1])

        where:
        op << ['AND', 'OR']
    }

    @Unroll
    def 'nested compound a=1 AND (b>2 OR c<3) with #record -> #expected'() {
        given:
        def filter = new CompoundFilter(
            [a1, [filters: [[field: 'b', op: '>', value: 2], [field: 'c', op: '<', value: 3]], op: 'OR']],
            'AND'
        )

        expect:
        filter.testFn.call(record) == expected

        where:
        record             | expected
        [a: 1, b: 3, c: 9] | true
        [a: 1, b: 0, c: 1] | true
        [a: 1, b: 0, c: 9] | false
        [a: 0, b: 3, c: 1] | false
    }

    def 'getAllFields is unique across nested children'() {
        given:
        def filter = new CompoundFilter(
            [[field: 'a', op: '=', value: 1],
             [filters: [[field: 'a', op: '=', value: 2], [field: 'b', op: '=', value: 2]]]],
            'AND'
        )

        expect:
        filter.allFields == ['a', 'b']
    }

    def 'criterion type follows op'() {
        expect:
        new CompoundFilter([a1, b2], 'AND').criterion instanceof Conjunction
        new CompoundFilter([a1, b2], 'OR').criterion instanceof Disjunction
    }

    def 'criterion of a single child is unwrapped'() {
        expect:
        !(new CompoundFilter([a1], 'AND').criterion instanceof Conjunction)
    }

    def 'criterion includes both children'() {
        when:
        def s = new CompoundFilter([a1, b2], 'AND').criterion.toString()

        then:
        s.contains('a')
        s.contains('b')
    }

    def 'criterion fails with a FunctionFilter child'() {
        when:
        new CompoundFilter([a1, { true }], 'AND').criterion

        then:
        thrown(RuntimeException)
    }

    def 'equals(Filter) is sensitive to op and child order'() {
        given:
        def base = new CompoundFilter([a1, b2], 'AND')

        expect:
        base.equals((Filter) new CompoundFilter([a1, b2], 'AND'))
        !base.equals((Filter) new CompoundFilter([a1, b2], 'OR'))
        !base.equals((Filter) new CompoundFilter([b2, a1], 'AND'))
    }

    def 'serializes to JSON and parses back'() {
        given:
        def filter = new CompoundFilter([a1, b2], 'OR')

        when:
        def json = JSONSerializer.serialize(filter)
        def parsed = Filter.parse(JSONParser.parseObject(json))

        then:
        JSONParser.parseObject(json).op == 'OR'
        JSONParser.parseObject(json).filters.size() == 2
        parsed instanceof CompoundFilter
        parsed.op == 'OR'
        parsed.filters*.field == ['a', 'b']
    }
}
