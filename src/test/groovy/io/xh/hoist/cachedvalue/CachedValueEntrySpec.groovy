/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.cachedvalue

import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.io.Output
import com.esotericsoftware.kryo.util.DefaultInstantiatorStrategy
import org.objenesis.strategy.StdInstantiatorStrategy
import spock.lang.Specification

class CachedValueEntrySpec extends Specification {

    def 'constructor assigns a unique uuid string and current time'() {
        given:
        long before = System.currentTimeMillis()

        when:
        def a = new CachedValueEntry('v', 'logger')
        def b = new CachedValueEntry('v', 'logger')

        then:
        a.uuid.length() == 36
        a.uuid != b.uuid
        a.dateEntered >= before
    }

    def 'uninitialized entry has no uuid, value or time'() {
        when:
        def entry = CachedValueEntry.createUninitializedCachedValueEntry('logger')

        then:
        entry.uuid == null
        entry.value == null
        entry.dateEntered == 0L
        entry.loggerName == 'logger'
    }

    def 'kryo roundtrip preserves value #value'() {
        given:
        def entry = new CachedValueEntry(value, 'my.logger')

        when:
        CachedValueEntry copy = roundtrip(entry)

        then:
        copy.value == value
        copy.uuid == entry.uuid
        copy.dateEntered == entry.dateEntered
        copy.loggerName == 'my.logger'

        where:
        value << ['str', [a: 1, b: [1, 2]], [1, 2, 3], null]
    }

    def 'kryo roundtrip of an uninitialized entry preserves null uuid'() {
        when:
        CachedValueEntry copy = roundtrip(CachedValueEntry.createUninitializedCachedValueEntry('my.logger'))

        then:
        copy.uuid == null
        copy.value == null
        copy.dateEntered == 0L
    }

    def 'instance log is named by loggerName'() {
        expect:
        new CachedValueEntry('v', 'my.logger').instanceLog.name == 'my.logger'
    }

    def 'instance log fails for a null loggerName'() {
        when:
        new CachedValueEntry('v', null).instanceLog

        then:
        thrown(Exception)
    }

    static <T> T roundtrip(T obj) {
        def kryo = new Kryo()
        kryo.registrationRequired = false
        kryo.instantiatorStrategy = new DefaultInstantiatorStrategy(new StdInstantiatorStrategy())
        def bytes = new ByteArrayOutputStream()
        def output = new Output(bytes)
        kryo.writeClassAndObject(output, obj)
        output.close()
        (T) kryo.readClassAndObject(new Input(bytes.toByteArray()))
    }
}
