/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.cache

import io.xh.hoist.BaseService
import io.xh.hoist.test.HoistSpec

import java.time.Instant
import java.util.concurrent.TimeoutException

import static java.lang.System.currentTimeMillis

/** Covers the local (non-replicated) behavior of {@link Cache}. */
class CacheSpec extends HoistSpec {

    static class PlainService extends BaseService {}

    PlainService svc

    def setup() {
        svc = createService(PlainService)
    }

    //-------------------
    // Basics
    //-------------------
    def 'put, get, getEntry and remove'() {
        given:
        Cache cache = newCache()

        when:
        cache.put('a', 1)

        then:
        cache.get('a') == 1
        cache.getEntry('a').value == 1
        cache.getEntry('a').key == 'a'
        cache.get('missing') == null
        cache.size() == 1

        when:
        cache.remove('a')

        then:
        cache.get('a') == null
        cache.size() == 0
    }

    def 'putting null removes the entry'() {
        given:
        Cache cache = newCache()
        cache.put('a', 1)

        when:
        cache.put('a', null)

        then:
        cache.getEntry('a') == null
    }

    def 'an empty cache is falsy and a populated one truthy'() {
        given:
        Cache cache = newCache()

        expect:
        !cache

        when:
        cache.put('a', 1)

        then:
        cache
    }

    def 'getOrCreate calls the closure with the key only when missing'() {
        given:
        Cache cache = newCache()
        def keys = []

        when:
        def first = cache.getOrCreate('a') { k -> keys << k; 'created' }
        def second = cache.getOrCreate('a') { k -> keys << k; 'again' }

        then:
        first == 'created'
        second == 'created'
        keys == ['a']
    }

    def 'clear removes all entries and notifies for each'() {
        given:
        def removed = []
        Cache cache = newCache(onChange: { CacheEntryChanged c -> if (c.value == null) removed << c.key })
        cache.put('a', 1)
        cache.put('b', 2)

        when:
        cache.clear()

        then:
        cache.size() == 0
        removed.toSet() == ['a', 'b'].toSet()
    }

    //-------------------
    // Expiry
    //-------------------
    def 'expireTime as closure=#asClosure with entry age #ageMs expires=#expired'() {
        given:
        Cache cache = newCache(expireTime: asClosure ? { -> 1000L } : 1000L, timestampFn: { Map v -> v.ts })
        cache.put('a', [ts: currentTimeMillis() - ageMs])

        expect:
        (cache.get('a') == null) == expired

        where:
        asClosure | ageMs | expired
        false     | 5000  | true
        false     | -5000 | false
        true      | 5000  | true
        true      | -5000 | false
    }

    def 'with neither expireTime nor expireFn entries never expire'() {
        given:
        Cache cache = newCache(timestampFn: { Map v -> v.ts })
        cache.put('a', [ts: 0L])

        expect:
        cache.get('a') != null
    }

    def 'expireFn takes precedence over expireTime'() {
        given:
        Cache cache = newCache(expireTime: 1L, expireFn: { false }, timestampFn: { Map v -> v.ts })
        cache.put('a', [ts: 0L])

        expect:
        cache.get('a') != null
    }

    def 'expireFn can expire selectively'() {
        given:
        Cache cache = newCache(expireFn: { CacheEntry e -> e.value == 'stale' })
        cache.put('a', 'stale')
        cache.put('b', 'fresh')

        expect:
        cache.get('a') == null
        cache.get('b') == 'fresh'
    }

    def 'timestampFn may return #desc'() {
        given:
        Cache cache = newCache(expireTime: 1000L, timestampFn: tsFn)
        cache.put('a', 'v')

        expect:
        cache.get('a') == null

        where:
        desc      | tsFn
        'Long'    | ({ v -> currentTimeMillis() - 5000 })
        'Date'    | ({ v -> new Date(currentTimeMillis() - 5000) })
        'Instant' | ({ v -> Instant.now().minusSeconds(5) })
    }

    def 'timestampFn returning an unsupported type fails'() {
        given:
        Cache cache = newCache(expireTime: 1000L, timestampFn: { 'not a time' })
        cache.put('a', 'v')

        when:
        cache.get('a')

        then:
        thrown(IllegalArgumentException)
    }

    def 'getTimestamp is entry time by default, timestampFn when set, null when missing'() {
        given:
        long before = currentTimeMillis()
        Cache plain = newCache()
        Cache custom = newCache(timestampFn: { Map v -> v.ts })
        plain.put('a', 1)
        custom.put('a', [ts: 1234L])

        expect:
        plain.getTimestamp('a') >= before
        plain.getTimestamp('a') <= currentTimeMillis()
        custom.getTimestamp('a') == 1234L
        plain.getTimestamp('missing') == null
    }

    def 'getMap culls expired entries but size over-reports until then'() {
        given:
        Cache cache = newCache(expireTime: 1000L, timestampFn: { Map v -> v.ts })
        cache.put('old', [ts: currentTimeMillis() - 5000])
        cache.put('new', [ts: currentTimeMillis() + 5000])

        expect:
        cache.size() == 2
        cache.getMap().keySet() == ['new'].toSet()
        cache.size() == 1
    }

    //-------------------
    // Change handlers
    //-------------------
    def 'handlers fire synchronously on put'() {
        given:
        def events = []
        Cache cache = newCache(onChange: { CacheEntryChanged c -> events << [c.key, c.value] })

        when:
        cache.put('a', 1)

        then:
        events == [['a', 1]]
    }

    def 'handlers fire with a null value on removal'() {
        given:
        def events = []
        Cache cache = newCache(onChange: { CacheEntryChanged c -> events << [c.key, c.value] })
        cache.put('a', 1)

        when:
        cache.remove('a')

        then:
        events == [['a', 1], ['a', null]]
    }

    def 'putting the identical instance twice fires once'() {
        given:
        def count = 0
        Cache cache = newCache(onChange: { count++ })
        def value = [x: 1]

        when:
        cache.put('a', value)
        cache.put('a', value)

        then:
        count == 1
    }

    def 'addChangeHandler appends handlers'() {
        given:
        def calls = []
        Cache cache = newCache(onChange: { calls << 'first' })
        cache.addChangeHandler { calls << 'second' }

        when:
        cache.put('a', 1)

        then:
        calls == ['first', 'second']
    }

    def 'lazy expiry on get fires a removal event'() {
        given:
        def events = []
        Cache cache = newCache(expireFn: { CacheEntry e -> e.value == 'stale' })
        cache.put('a', 'stale')
        cache.addChangeHandler { CacheEntryChanged c -> events << [c.key, c.value] }

        when:
        cache.get('a')

        then:
        events == [['a', null]]
    }

    def 'oldValue is #desc depending on serializeOldValue=#serialize'() {
        given:
        def old = []
        Cache cache = newCache(serializeOldValue: serialize, onChange: { CacheEntryChanged c -> old << c.oldValue })
        cache.put('a', 'first')

        when:
        cache.put('a', 'second')

        then:
        old == [null, expected]

        where:
        serialize | expected | desc
        true      | 'first'  | 'available'
        false     | null     | 'withheld'
    }

    //-------------------
    // ensureAvailable
    //-------------------
    def 'ensureAvailable returns immediately for an existing key'() {
        given:
        Cache cache = newCache()
        cache.put('a', 1)

        when:
        cache.ensureAvailable('a', timeout: 50L, interval: 10L)

        then:
        noExceptionThrown()
    }

    def 'ensureAvailable times out with a default message naming the key'() {
        given:
        Cache cache = newCache()

        when:
        cache.ensureAvailable('missing', timeout: 50L, interval: 10L)

        then:
        def e = thrown(TimeoutException)
        e.message.contains("'missing'")
    }

    def 'ensureAvailable uses a custom timeout message verbatim'() {
        given:
        Cache cache = newCache()

        when:
        cache.ensureAvailable('missing', timeout: 50L, interval: 10L, timeoutMessage: 'custom')

        then:
        def e = thrown(TimeoutException)
        e.message == 'custom'
    }

    def 'ensureAvailable returns once another thread populates the entry'() {
        given:
        Cache cache = newCache()
        def writer = Thread.start { cache.put('a', 1) }

        when:
        cache.ensureAvailable('a', timeout: 5000L, interval: 10L)

        then:
        noExceptionThrown()
        cache.get('a') == 1

        cleanup:
        writer?.join()
    }

    //-------------------
    // Admin stats
    //-------------------
    def 'admin stats describe the cache'() {
        given:
        Cache cache = newCache(name: 'statsCache')
        cache.put('a', 1)

        when:
        def stats = cache.adminStats

        then:
        stats.name == 'statsCache'
        stats.type == 'Cache'
        stats.replicate == false
        stats.count == 1
        stats.latestTimestamp instanceof Long
        stats.containsKey('lastCullTime')
        cache.comparableAdminStats == []
    }

    private Cache newCache(Map args = [:]) {
        svc.createCache([name: "cache${System.nanoTime()}".toString()] + args)
    }
}
