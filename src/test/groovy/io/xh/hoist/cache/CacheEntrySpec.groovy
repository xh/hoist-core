/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.cache

import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.io.Output
import org.objenesis.strategy.StdInstantiatorStrategy
import com.esotericsoftware.kryo.util.DefaultInstantiatorStrategy
import spock.lang.Specification

class CacheEntrySpec extends Specification {

    def 'constructor stamps entry time and enables value serialization'() {
        given:
        long before = System.currentTimeMillis()

        when:
        def entry = new CacheEntry('k', 'v', 'logger.name')

        then:
        entry.serializeValue
        entry.dateEntered >= before
        entry.dateEntered <= System.currentTimeMillis()
    }

    def 'kryo roundtrip preserves key #key and value #value'() {
        given:
        def entry = new CacheEntry(key, value, 'my.logger')

        when:
        CacheEntry copy = roundtrip(entry)

        then:
        copy.key == key
        copy.value == value
        copy.dateEntered == entry.dateEntered
        copy.loggerName == 'my.logger'
        copy.serializeValue

        where:
        key                 | value
        'a'                 | 'string'
        7                   | [x: 1, y: [2, 3]]
        [id: 1, name: 'cx'] | ['a', 'b']
    }

    def 'with serializeValue false only the flag is written'() {
        given:
        def entry = new CacheEntry('k', 'v', 'my.logger')
        entry.serializeValue = false

        when:
        CacheEntry copy = roundtrip(entry)

        then:
        !copy.serializeValue
        copy.key == null
        copy.value == null
        copy.dateEntered == null
        copy.loggerName == null
    }

    def 'instance log is named by loggerName'() {
        expect:
        new CacheEntry('k', 'v', 'my.logger').instanceLog.name == 'my.logger'
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
