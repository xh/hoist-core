/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.cachedvalue

import io.xh.hoist.BaseService
import io.xh.hoist.cache.Cache
import io.xh.hoist.test.HoistSpec
import spock.util.concurrent.PollingConditions

import java.util.concurrent.TimeoutException

import static java.lang.System.currentTimeMillis

/** Covers the local (non-replicated) behavior of {@link CachedValue}. */
class CachedValueSpec extends HoistSpec {

    static class PlainService extends BaseService {}

    PlainService svc

    def setup() {
        svc = createService(PlainService)
    }

    def 'starts empty with a zero timestamp'() {
        given:
        CachedValue cv = newValue()

        expect:
        cv.get() == null
        cv.timestamp == 0L
        !cv
    }

    def 'set, get and clear roundtrip'() {
        given:
        CachedValue cv = newValue()
        long before = currentTimeMillis()

        when:
        cv.set('v')

        then:
        cv.get() == 'v'
        cv
        cv.timestamp >= before
        cv.timestamp <= currentTimeMillis()

        when:
        cv.clear()

        then:
        cv.get() == null
        !cv
    }

    def 'getOrCreate only calls the closure when empty'() {
        given:
        CachedValue cv = newValue()
        int calls = 0

        when:
        def first = cv.getOrCreate { calls++; 'created' }
        def second = cv.getOrCreate { calls++; 'again' }

        then:
        first == 'created'
        second == 'created'
        calls == 1
    }

    def 'getOrCreate recreates an expired value'() {
        given:
        CachedValue cv = newValue(expireFn: { true })
        cv.set('old')

        expect:
        cv.getOrCreate { 'new' } == 'new'
    }

    def 'an expired value reads as null and is cleared'() {
        given:
        CachedValue cv = newValue(expireTime: 1000L, timestampFn: { Map v -> v.ts })
        cv.set([ts: currentTimeMillis() - 5000])

        expect:
        cv.get() == null
        cv.entry.value == null
    }

    def 'a value inside its expireTime is returned'() {
        given:
        CachedValue cv = newValue(expireTime: 1000L, timestampFn: { Map v -> v.ts })
        cv.set([ts: currentTimeMillis() + 5000])

        expect:
        cv.get() != null
    }

    def 'expireTime closures are re-evaluated on each check'() {
        given:
        def ttl = 100_000L
        CachedValue cv = newValue(expireTime: { -> ttl }, timestampFn: { Map v -> v.ts })
        cv.set([ts: currentTimeMillis() - 5000])

        expect:
        cv.get() != null

        when:
        ttl = 1000L
        cv.set([ts: currentTimeMillis() - 5000])

        then:
        cv.get() == null
    }

    def 'expireTime of #expireTime #behavior for CachedValue'() {
        given: 'CachedValue uses truthiness, so a zero expireTime is treated as no expiry'
        CachedValue cv = newValue(expireTime: expireTime, timestampFn: { Map v -> v.ts })
        cv.set([ts: currentTimeMillis() - 60_000])

        expect:
        (cv.get() == null) == expires

        where:
        expireTime | expires | behavior
        0          | false   | 'never expires'
        0L         | false   | 'never expires'
        1000L      | true    | 'expires'
    }

    def 'Cache treats an expireTime of 0 differently from CachedValue'() {
        given: 'by contrast Cache tests for null, so zero expires entries stamped in the past'
        Cache cache = svc.createCache(name: 'zeroTtl', expireTime: 0L, timestampFn: { Map v -> v.ts })
        cache.put('a', [ts: currentTimeMillis() - 60_000])

        expect:
        cache.get('a') == null
    }

    //-------------------
    // Change handlers
    //-------------------
    def 'handlers are fired asynchronously with old and new values'() {
        given:
        def changes = new java.util.concurrent.CopyOnWriteArrayList()
        CachedValue cv = newValue(onChange: { CachedValueChanged c -> changes << [c.oldValue, c.value] })

        when:
        cv.set('a')
        cv.set('b')

        then:
        new PollingConditions(timeout: 5).eventually {
            assert changes.toSet() == [[null, 'a'], ['a', 'b']].toSet()
        }
    }

    def 'handlers fire with a null value on clear'() {
        given:
        def changes = new java.util.concurrent.CopyOnWriteArrayList()
        CachedValue cv = newValue()
        cv.set('a')
        cv.addChangeHandler { CachedValueChanged c -> changes << [c.oldValue, c.value] }

        when:
        cv.clear()

        then:
        new PollingConditions(timeout: 5).eventually {
            assert changes == [['a', null]]
        }
    }

    def 'setting the identical instance twice fires once'() {
        given:
        def values = new java.util.concurrent.CopyOnWriteArrayList()
        CachedValue cv = newValue(onChange: { CachedValueChanged c -> values << c.value })
        def value = [x: 1]

        when: 'handlers run concurrently, so wait for both the first and a trailing sentinel handler'
        cv.set(value)
        cv.set(value)
        cv.set('sentinel')

        then:
        new PollingConditions(timeout: 5).eventually {
            assert values.contains('sentinel') && values.any { it.is(value) }
        }
        values.count { it.is(value) } == 1
    }

    def 'setInternal ignores an entry with the same uuid as the current one'() {
        given:
        def values = new java.util.concurrent.CopyOnWriteArrayList()
        CachedValue cv = newValue(onChange: { CachedValueChanged c -> values << c.value })
        def entry = new CachedValueEntry('v', 'logger')

        when:
        cv.setInternal(entry, false)
        cv.setInternal(entry, false)
        cv.set('sentinel')

        then:
        new PollingConditions(timeout: 5).eventually {
            assert values.contains('sentinel') && values.contains('v')
        }
        values.count { it == 'v' } == 1
    }

    def 'the initial uninitialized entry carries the logger name'() {
        given:
        CachedValue cv = newValue(name: 'named')

        expect:
        cv.@entry.loggerName != null
    }

    //-------------------
    // ensureAvailable
    //-------------------
    def 'ensureAvailable returns immediately when set'() {
        given:
        CachedValue cv = newValue()
        cv.set('v')

        when:
        cv.ensureAvailable(timeout: 50L, interval: 10L)

        then:
        noExceptionThrown()
    }

    def 'ensureAvailable times out with a message naming the value'() {
        given:
        CachedValue cv = newValue(name: 'slowValue')

        when:
        cv.ensureAvailable(timeout: 50L, interval: 10L)

        then:
        def e = thrown(TimeoutException)
        e.message.contains("'slowValue'")
    }

    def 'ensureAvailable honors a custom timeout message'() {
        given:
        CachedValue cv = newValue()

        when:
        cv.ensureAvailable(timeout: 50L, interval: 10L, timeoutMessage: 'custom')

        then:
        def e = thrown(TimeoutException)
        e.message == 'custom'
    }

    //-------------------
    // Admin stats
    //-------------------
    def 'admin stats include size only for collections and maps'() {
        given:
        CachedValue cv = newValue(name: 'stats')
        cv.set(value)

        when:
        def stats = cv.adminStats

        then:
        stats.name == 'stats'
        stats.type == 'CachedValue'
        stats.replicate == false
        stats.containsKey('size') == hasSize
        cv.comparableAdminStats == []

        where:
        value        | hasSize
        [1, 2, 3]    | true
        [a: 1]       | true
        'a string'   | false
    }

    private CachedValue newValue(Map args = [:]) {
        svc.createCachedValue([name: "value${System.nanoTime()}".toString()] + args)
    }
}
