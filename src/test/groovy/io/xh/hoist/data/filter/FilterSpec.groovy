/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.data.filter

import io.xh.hoist.json.JSONSerializer
import io.xh.hoist.test.HoistSpec
import spock.lang.Unroll

class FilterSpec extends HoistSpec {

    @Unroll
    def 'parse returns null for falsey spec #spec'() {
        expect:
        Filter.parse(spec) == null

        where:
        spec << [null, [:], [], '', false]
    }

    def 'parse returns an existing Filter as-is'() {
        given:
        def filter = new FieldFilter('a', '=', 1)

        expect:
        Filter.parse(filter).is(filter)
    }

    def 'parse wraps a closure in a FunctionFilter'() {
        given:
        def fn = { it.a == 1 }

        when:
        def filter = Filter.parse(fn)

        then:
        filter instanceof FunctionFilter
        filter.testFn.is(fn)
    }

    def 'parse accepts a testFn map'() {
        given:
        def fn = { true }

        expect:
        Filter.parse([testFn: fn]).testFn.is(fn)
    }

    def 'parse creates a FieldFilter from a map with a field'() {
        when:
        def filter = Filter.parse([field: 'a', op: '>', value: 2])

        then:
        filter instanceof FieldFilter
        filter.field == 'a'
        filter.op == '>'
        filter.value == 2
    }

    def 'field takes precedence over filters'() {
        expect:
        Filter.parse([field: 'a', op: '=', value: 1, filters: [[field: 'b', op: '=', value: 2]]]) instanceof FieldFilter
    }

    def 'parse unwraps a list containing a single spec'() {
        expect:
        Filter.parse([[field: 'a', op: '=', value: 1]]) instanceof FieldFilter
    }

    def 'parse creates an AND CompoundFilter from a list of specs'() {
        when:
        def filter = Filter.parse([[field: 'a', op: '=', value: 1], [field: 'b', op: '=', value: 2]])

        then:
        filter instanceof CompoundFilter
        filter.op == 'AND'
        filter.filters.size() == 2
    }

    def 'parse returns null for a list of only empty specs'() {
        expect:
        Filter.parse([null, [:]]) == null
    }

    def 'parse builds nested trees and uppercases the op'() {
        when:
        def filter = Filter.parse([[field: 'a', op: '=', value: 1],
                                   [filters: [[field: 'b', op: '>', value: 2], [field: 'c', op: '<', value: 3]], op: 'or']])

        then:
        filter instanceof CompoundFilter
        filter.filters[1] instanceof CompoundFilter
        filter.filters[1].op == 'OR'
        filter.allFields == ['a', 'b', 'c']
    }

    def 'parse throws for a map with unrecognized shape'() {
        when:
        Filter.parse(spec)

        then:
        thrown(RuntimeException)

        where:
        spec << [[op: 'OR'], [filters: []]]
    }

    def 'parse propagates FieldFilter validation errors'() {
        when:
        Filter.parse([field: 'a', op: 'xx'])

        then:
        thrown(IllegalArgumentException)
    }

    //------------------
    // FunctionFilter
    //------------------
    def 'FunctionFilter has no fields and no criterion'() {
        given:
        def filter = new FunctionFilter({ true })

        expect:
        filter.allFields == []

        when:
        filter.criterion

        then:
        thrown(RuntimeException)
    }

    def 'FunctionFilter equality is closure identity'() {
        given:
        def fn = { true }
        def a = new FunctionFilter(fn)

        expect:
        a.equals((Filter) new FunctionFilter(fn))
        !a.equals((Filter) new FunctionFilter({ true }))
        !a.equals((Filter) new FieldFilter('a', '=', 1))
    }

    def 'FunctionFilter tests records using its closure'() {
        expect:
        Filter.parse({ it.x > 1 }).testFn.call([x: 2])
        !Filter.parse({ it.x > 1 }).testFn.call([x: 0])
    }

    def 'serializing a FunctionFilter fails'() {
        when:
        JSONSerializer.serialize(Filter.parse({ true }))

        then:
        thrown(Exception)
    }
}
