/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.cache

import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.KryoSerializable
import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.io.Output
import groovy.transform.CompileStatic
import io.xh.hoist.log.LogSupport
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import static java.lang.System.currentTimeMillis

@CompileStatic
class CacheEntry<K, T> implements KryoSerializable, LogSupport {
    K key
    T value
    Long dateEntered
    String loggerName

    boolean serializeValue

    CacheEntry(K key, T value, String loggerName) {
        this.key = key
        this.value = value
        this.dateEntered = currentTimeMillis()
        this.loggerName = loggerName
        this.serializeValue = true
    }

    CacheEntry() {}

    void write(Kryo kryo, Output output) {
        output.writeBoolean(serializeValue)
        if (!serializeValue) return

        kryo.writeClassAndObject(output, key)
        output.writeLong(dateEntered)
        output.writeString(loggerName)
        withSingleTrace('Serializing') {
            kryo.writeClassAndObject(output, value)
        }
    }

    void read(Kryo kryo, Input input) {
        serializeValue = input.readBoolean()
        if (!serializeValue) return

        key =  kryo.readClassAndObject(input) as K
        dateEntered = input.readLong()
        loggerName = input.readString()
        withSingleTrace('Deserializing') {
            value = kryo.readClassAndObject(input) as T
        }
    }

    Logger getInstanceLog() {
        LoggerFactory.getLogger(loggerName)
    }

    private void withSingleTrace(String msg, Closure c) {
        Long start = currentTimeMillis()
        c()
        logTrace(msg, key, [_elapsedMs: currentTimeMillis() - start])
    }
}
