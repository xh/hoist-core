/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.util

import spock.lang.Specification

import java.util.function.Function

class CollectionUtilsSpec extends Specification {

    def 'quickMap builds a map from alternating keys and values'() {
        expect:
        CollectionUtils.quickMap() == [:]
        CollectionUtils.quickMap('a', 1, 'b', 2) == [a: 1, b: 2]
        CollectionUtils.quickMap('a', 1, 'a', 2) == [a: 2]
        CollectionUtils.quickMap(null, null) == [(null): null]
    }

    def 'quickMap rejects an odd number of arguments'() {
        when:
        CollectionUtils.quickMap('a', 1, 'b')

        then:
        thrown(IllegalArgumentException)
    }

    def 'sizedHashMap returns an empty map'() {
        expect:
        CollectionUtils.sizedHashMap(size) == [:]

        where:
        size << [0, 1, 1000]
    }

    def 'collect maps in order'() {
        expect:
        CollectionUtils.collect([1, 2, 3], { it * 2 } as Function) == [2, 4, 6]
        CollectionUtils.collect([], { it * 2 } as Function) == []
        CollectionUtils.collect([1, 2] as Set, { it + 1 } as Function) == [2, 3]
    }

    def 'collectEntries builds a map from mapped entries'() {
        expect:
        CollectionUtils.collectEntries(['a', 'bb'], { new AbstractMap.SimpleEntry(it, it.size()) } as Function) == [a: 1, bb: 2]
        CollectionUtils.collectEntries(['a', 'a'], { new AbstractMap.SimpleEntry(it, 1) } as Function) == [a: 1]
        CollectionUtils.collectEntries([], { new AbstractMap.SimpleEntry(it, 1) } as Function) == [:]
    }

    def 'collectEntries with later duplicate keys keeps the last'() {
        given:
        def n = 0

        expect:
        CollectionUtils.collectEntries(['a', 'a'], { new AbstractMap.SimpleEntry(it, ++n) } as Function) == [a: 2]
    }

    def 'null collections are not supported'() {
        when:
        CollectionUtils.collect(null, { it } as Function)

        then:
        thrown(NullPointerException)
    }
}
