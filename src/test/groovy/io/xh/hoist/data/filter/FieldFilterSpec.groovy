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
import org.hibernate.criterion.InExpression
import spock.lang.Unroll

import java.time.LocalDate

class FieldFilterSpec extends HoistSpec {

    //------------------
    // Construction
    //------------------
    def 'constructor rejects invalid field or operator'() {
        when:
        new FieldFilter(field, op, 1)

        then:
        thrown(IllegalArgumentException)

        where:
        field | op
        null  | '='
        ''    | '='
        'a'   | 'bogus'
        'a'   | null
    }

    def 'constructor rejects multiple values for comparison operators'() {
        when:
        new FieldFilter('a', op, [1, 2])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('does not support multiple values')

        where:
        op << ['>', '>=', '<', '<=']
    }

    def 'multi-value operators are all operators except the comparison operators'() {
        expect:
        FieldFilter.OPERATORS - FieldFilter.MULTI_VAL_OPERATORS == ['>', '>=', '<', '<=']
    }

    def 'collection values are copied, deduplicated and sorted'() {
        given:
        def input = [3, 1, 3]

        when:
        def filter = new FieldFilter('f', '=', input)

        then:
        filter.value == [1, 3]
        input == [3, 1, 3]
    }

    def 'set values become a sorted list'() {
        when:
        def filter = new FieldFilter('f', '=', [2, 1] as Set)

        then:
        filter.value instanceof List
        filter.value == [1, 2]
    }

    def 'getAllFields returns the single field'() {
        expect:
        new FieldFilter('a', '=', 1).allFields == ['a']
    }

    //------------------
    // getTestFn
    //------------------
    @Unroll
    def 'equals test fn: #record vs #value -> #expected'() {
        expect:
        new FieldFilter('f', '=', value).testFn.call(record) == expected

        where:
        value     | record    | expected
        1         | [f: 1]    | true
        1         | [f: 2]    | false
        1         | [f: 1L]   | true
        [1, 2]    | [f: 2]    | true
        [1, 2]    | [f: 3]    | false
        null      | [f: null] | true
        null      | [f: '']   | true
        null      | [:]       | true
        null      | [f: 'a']  | false
        [null, 1] | [f: '']   | true
    }

    @Unroll
    def 'not-equals test fn: #record vs #value -> #expected'() {
        expect:
        new FieldFilter('f', '!=', value).testFn.call(record) == expected

        where:
        value       | record    | expected
        'a'         | [f: 'b']  | true
        'a'         | [f: 'a']  | false
        'a'         | [f: null] | true
        'a'         | [f: '']   | true
        [null, 'a'] | [f: null] | false
        [null, 'a'] | [f: '']   | false
        [null, 'a'] | [f: 'a']  | false
        [null, 'a'] | [f: 'b']  | true
    }

    @Unroll
    def 'comparison #op with #value vs #record -> #expected'() {
        expect:
        new FieldFilter('f', op, value).testFn.call([f: record]) == expected

        where:
        op   | value | record | expected
        '>'  | 5     | 6      | true
        '>'  | 5     | 5      | false
        '>=' | 5     | 5      | true
        '>=' | 5     | 4      | false
        '<'  | 5     | 4      | true
        '<'  | 5     | 5      | false
        '<=' | 5     | 5      | true
        '<=' | 5     | 6      | false
        '>'  | 5     | null   | false
        '>=' | 5     | null   | false
        '<'  | 5     | null   | false
        '<=' | 5     | null   | false
        '>'  | 'b'   | 'c'    | true
        '<'  | 'b'   | 'a'    | true
    }

    def 'comparison works for dates and local dates'() {
        expect:
        new FieldFilter('f', '>', new Date(1000)).testFn.call([f: new Date(2000)])
        !new FieldFilter('f', '>', new Date(1000)).testFn.call([f: new Date(1000)])
        new FieldFilter('f', '<=', LocalDate.of(2024, 1, 5)).testFn.call([f: LocalDate.of(2024, 1, 5)])
    }

    @Unroll
    def 'text operator #op #value on #record -> #expected'() {
        expect:
        new FieldFilter('f', op, value).testFn.call([f: record]) == expected

        where:
        op           | value       | record | expected
        'like'       | 'oo'        | 'Foo'  | true
        'like'       | 'OO'        | 'foo'  | true
        'like'       | 'x'         | 'Foo'  | false
        'like'       | 'oo'        | null   | false
        'like'       | ['x', 'oo'] | 'Foo'  | true
        'not like'   | 'oo'        | 'Foo'  | false
        'not like'   | 'x'         | 'Foo'  | true
        'not like'   | 'oo'        | null   | true
        'begins'     | 'f'         | 'Foo'  | true
        'begins'     | 'o'         | 'Foo'  | false
        'begins'     | 'f'         | null   | false
        'not begins' | 'f'         | 'Foo'  | false
        'not begins' | 'o'         | 'Foo'  | true
        'not begins' | 'f'         | null   | true
        'ends'       | 'O'         | 'Foo'  | true
        'ends'       | 'F'         | 'Foo'  | false
        'ends'       | 'o'         | null   | false
        'not ends'   | 'o'         | 'Foo'  | false
        'not ends'   | 'f'         | 'Foo'  | true
        'not ends'   | 'o'         | null   | true
    }

    def 'like operator treats value literally'() {
        expect:
        !new FieldFilter('f', 'like', 'a.c').testFn.call([f: 'abc'])
    }

    def 'like operator tolerates regex metacharacters'() {
        expect:
        new FieldFilter('f', 'like', '(').testFn.call([f: 'a(b'])
    }

    def 'like operator on a non-string record value'() {
        expect:
        new FieldFilter('f', 'like', '2').testFn.call([f: 123])
    }

    @Unroll
    def 'text operator #op matches #value literally against #record -> #expected'() {
        expect:
        new FieldFilter('f', op, value).testFn.call([f: record]) == expected

        where:
        op           | value | record  | expected
        'begins'     | '.b'  | 'ab'    | false
        'begins'     | '(a'  | '(abc'  | true
        'ends'       | 'c$'  | 'abc$'  | true
        'ends'       | 'b.'  | 'abc'   | false
        'not like'   | 'a.c' | 'abc'   | true
        'not begins' | '['   | '[x'    | false
    }

    @Unroll
    def 'collection operator #op #value on #record -> #expected'() {
        expect:
        new FieldFilter('tags', op, value).testFn.call([tags: record]) == expected

        where:
        op         | value      | record     | expected
        'includes' | 'b'        | ['a', 'b'] | true
        'includes' | 'z'        | ['a', 'b'] | false
        'includes' | ['z', 'a'] | ['a', 'b'] | true
        'includes' | 'a'        | null       | false
        'excludes' | 'b'        | ['a', 'b'] | false
        'excludes' | 'z'        | ['a', 'b'] | true
        'excludes' | 'a'        | null       | true
    }

    //------------------
    // getCriterion
    //------------------
    def 'criterion for = with a single value is an in expression'() {
        expect:
        new FieldFilter('f', '=', 1).criterion instanceof InExpression
    }

    def 'criterion for = with null adds an is-null disjunction'() {
        when:
        def c = new FieldFilter('f', '=', [1, null]).criterion

        then:
        c instanceof Disjunction
        c.toString().contains('f is null')
        c.toString().contains('f in')
    }

    def 'criterion for != with multiple values is a null-or-conjunction'() {
        when:
        def c = new FieldFilter('f', '!=', [1, 2]).criterion

        then:
        c instanceof Disjunction
        c.toString().contains('f is null')
        c.toString().contains('f<>1')
        c.toString().contains('f<>2')
    }

    def 'criterion for != including null requires not-null'() {
        when:
        def c = new FieldFilter('f', '!=', [1, null]).criterion

        then:
        c instanceof Conjunction
        c.toString().contains('f is not null')
        c.toString().contains('f<>1')
    }

    @Unroll
    def 'criterion for #op is a simple restriction'() {
        expect:
        new FieldFilter('f', op, 1).criterion.toString() == expected

        where:
        op   | expected
        '>'  | 'f>1'
        '>=' | 'f>=1'
        '<'  | 'f<1'
        '<=' | 'f<=1'
    }

    def 'criterion for not like allows null fields'() {
        when:
        def c = new FieldFilter('f', 'not like', 'x').criterion

        then:
        c instanceof Disjunction
        c.toString().contains('f is null')
        c.toString().contains('not')
    }

    def 'criterion for like with multiple values is a disjunction'() {
        expect:
        new FieldFilter('f', 'like', ['a', 'b']).criterion instanceof Disjunction
        !(new FieldFilter('f', 'like', 'a').criterion instanceof Disjunction)
    }

    @Unroll
    def 'criterion for #op is unsupported'() {
        when:
        new FieldFilter('f', op, 'a').criterion

        then:
        thrown(RuntimeException)

        where:
        op << ['includes', 'excludes']
    }

    //------------------
    // Equality and JSON
    //------------------
    def 'equals(Filter) compares field, op and value'() {
        given:
        def base = new FieldFilter('f', '=', [1, 2])

        expect:
        base.equals((Filter) new FieldFilter('f', '=', [2, 1]))
        !base.equals((Filter) new FieldFilter('f', '!=', [1, 2]))
        !base.equals((Filter) new FieldFilter('g', '=', [1, 2]))
        !base.equals((Filter) new FieldFilter('f', '=', [1, 3]))
        base.equals((Filter) base)
    }

    def 'equal filters are equal as objects'() {
        given:
        def a = new FieldFilter('f', '=', 1)
        def b = new FieldFilter('f', '=', 1)

        expect:
        a == b
        new HashSet([a, b]).size() == 1
        a != new FieldFilter('f', '=', 2)
        a != 'not a filter'
    }

    def 'equal compound filters are equal as objects'() {
        given:
        def a = new CompoundFilter([[field: 'f', op: '=', value: 1], [field: 'g', op: '>', value: 2]], 'OR')
        def b = new CompoundFilter([[field: 'f', op: '=', value: 1], [field: 'g', op: '>', value: 2]], 'or')

        expect:
        a == b
        new HashSet([a, b]).size() == 1
    }

    def 'serializes to JSON and parses back'() {
        given:
        def filter = new FieldFilter('a', '=', [2, 1])

        when:
        def json = JSONSerializer.serialize(filter)
        def parsed = Filter.parse(JSONParser.parseObject(json))

        then:
        json == '{"field":"a","op":"=","value":[1,2]}'
        parsed instanceof FieldFilter
        parsed.equals((Filter) filter)
    }
}
