/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.util

import spock.lang.Specification
import spock.lang.Unroll

class StringUtilsSpec extends Specification {

    @Unroll
    def 'elide(#str, #len) -> #expected'() {
        expect:
        StringUtils.elide(str, len) == expected

        where:
        str                    | len | expected
        null                   | 5   | null
        ''                     | 5   | ''
        'abc'                  | 5   | 'abc'
        'hello world foo'      | 12  | 'hello...'
        'Hello, world!'        | 9   | 'Hello,...'
        'supercalifragilistic' | 10  | '...'
        'ab'                   | 2   | 'ab'
        'abcd'                 | 3   | '...'
    }

    def 'string exactly len characters long is returned unchanged'() {
        expect:
        StringUtils.elide('hello', 5) == 'hello'
    }

    def 'elide with len below 3 and a long string throws'() {
        when:
        StringUtils.elide('hello world', 2)

        then:
        thrown(StringIndexOutOfBoundsException)
    }

    @Unroll
    def 'result never exceeds len (#str, #len)'() {
        expect:
        StringUtils.elide(str, len).size() <= len

        where:
        [str, len] << [
            ['The quick brown fox jumps over the lazy dog', 'a b c d e f g h i j', 'one   two   three four', 'x' * 50],
            [3, 4, 8, 15, 30]
        ].combinations()
    }
}
