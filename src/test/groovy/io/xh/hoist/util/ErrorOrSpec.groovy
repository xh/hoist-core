/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.util

import io.xh.hoist.json.JSONSerializer
import io.xh.hoist.test.HoistSpec
import spock.lang.Unroll

class ErrorOrSpec extends HoistSpec {

    def 'of wraps a value as a success'() {
        when:
        def result = ErrorOr.of(5)

        then:
        result.success
        result.value == 5
        result.error == null
    }

    def 'of null is still a success'() {
        when:
        def result = ErrorOr.of(null)

        then:
        result.success
        result.value == null
    }

    def 'error wraps a message as a failure'() {
        when:
        def result = ErrorOr.error('bad')

        then:
        !result.success
        result.value == null
        result.error == 'bad'
    }

    @Unroll
    def 'error with #input uses a default message'() {
        expect:
        ErrorOr.error(input).error == 'Unknown error'
        !ErrorOr.error(input).success

        where:
        input << [null, '']
    }

    def 'formatForJSON returns the bare value or the bare error'() {
        expect:
        ErrorOr.of([x: 1]).formatForJSON() == [x: 1]
        ErrorOr.error('nope').formatForJSON() == 'nope'
    }

    def 'serializes as the bare value or error within a larger structure'() {
        expect:
        JSONSerializer.serialize([a: ErrorOr.of([x: 1]), b: ErrorOr.error('nope')]) == '{"a":{"x":1},"b":"nope"}'
    }
}
