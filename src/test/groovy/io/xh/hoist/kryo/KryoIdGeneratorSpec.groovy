/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.kryo

import com.hazelcast.core.HazelcastInstance
import spock.lang.Specification

import java.util.concurrent.Callable
import java.util.concurrent.Executors

class KryoIdGeneratorSpec extends Specification {

    List<HazelcastInstance> instances = []

    def cleanup() {
        instances.each { KryoIdGenerator.instanceDestroyed(it) }
    }

    def 'first id for a new instance is one above the base id'() {
        expect:
        KryoIdGenerator.globalId(newInstance()) == 6001
    }

    def 'repeat calls for the same instance return the same id'() {
        given:
        def hz = newInstance()

        expect:
        KryoIdGenerator.globalId(hz) == KryoIdGenerator.globalId(hz)
    }

    def 'each instance gets its own sequence'() {
        expect:
        KryoIdGenerator.globalId(newInstance()) == 6001
        KryoIdGenerator.globalId(newInstance()) == 6001
    }

    def 'destroying an instance discards its sequence state'() {
        given:
        def hz = newInstance()
        KryoIdGenerator.globalId(hz)

        when:
        KryoIdGenerator.instanceDestroyed(hz)

        then:
        !KryoIdGenerator.counterMap.containsKey(hz)
        KryoIdGenerator.globalId(hz) == 6001
    }

    def 'concurrent callers observe a single id'() {
        given:
        def hz = newInstance()
        def pool = Executors.newFixedThreadPool(16)

        when:
        def ids = (1..32).collect { pool.submit({ KryoIdGenerator.globalId(hz) } as Callable<Integer>) }*.get()

        then:
        ids.toSet() == [6001].toSet()

        cleanup:
        pool.shutdownNow()
    }

    private HazelcastInstance newInstance() {
        def hz = Stub(HazelcastInstance)
        instances << hz
        hz
    }
}
