/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.json

import spock.lang.Specification

import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class JSONFormatCachedSpec extends Specification {

    static class Counted extends JSONFormatCached implements Serializable {
        transient AtomicInteger calls = new AtomicInteger()
        Map state = [a: 1]

        protected Object formatForJSON() {
            calls?.incrementAndGet()
            state
        }
    }

    def 'formatForJSON is invoked once and the same string is returned'() {
        given:
        def obj = new Counted()

        when:
        def first = obj.cachedJSON
        def second = obj.cachedJSON
        def third = JSONSerializer.serialize(obj)

        then:
        first == '{"a":1}'
        first.is(second)
        third == first
        obj.calls.get() == 1
    }

    def 'cached JSON is embedded as raw JSON within collections'() {
        expect:
        JSONSerializer.serialize([new Counted(), new Counted()]) == '[{"a":1},{"a":1}]'
        JSONSerializer.serialize([k: new Counted()]) == '{"k":{"a":1}}'
    }

    def 'later changes to backing state do not affect the cached output'() {
        given:
        def obj = new Counted()
        def before = obj.cachedJSON

        when:
        obj.state = [a: 2]

        then:
        obj.cachedJSON == before
    }

    def 'concurrent first access computes the value exactly once'() {
        given:
        def obj = new Counted()
        def threads = 8
        def pool = Executors.newFixedThreadPool(threads)
        def ready = new CountDownLatch(threads)
        def go = new CountDownLatch(1)

        when:
        def futures = (1..threads).collect {
            pool.submit({ ready.countDown(); go.await(); obj.cachedJSON } as Callable)
        }
        ready.await(10, TimeUnit.SECONDS)
        go.countDown()
        def results = futures*.get(10, TimeUnit.SECONDS)

        then:
        results.toSet() == ['{"a":1}'].toSet()
        obj.calls.get() == 1

        cleanup:
        pool.shutdownNow()
    }

    def 'java serialization drops the transient cache which is rebuilt lazily'() {
        given:
        def obj = new Counted()
        obj.cachedJSON
        def bos = new ByteArrayOutputStream()
        def oos = new ObjectOutputStream(bos)
        oos.writeObject(obj)
        oos.flush()

        when:
        def copy = (Counted) new ObjectInputStream(new ByteArrayInputStream(bos.toByteArray())).readObject()
        def cacheField = JSONFormatCached.getDeclaredField('_cache')
        cacheField.accessible = true

        then:
        cacheField.get(copy) == null
        copy.cachedJSON == '{"a":1}'
        cacheField.get(copy) != null
    }
}
