/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.cache

import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.KryoSerializable
import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.io.Output
import io.xh.hoist.log.LogSupport
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import static java.lang.System.currentTimeMillis

class CachedValueEntry<T> implements KryoSerializable, LogSupport {
    Long dateEntered
    String loggerName
    T value

    CachedValueEntry(T value, String loggerName) {
        this.dateEntered = currentTimeMillis()
        this.loggerName = loggerName
        this.value = value
    }

    CachedValueEntry() {}

    void write(Kryo kryo, Output output) {
        output.writeLong(dateEntered)
        output.writeString(loggerName)
        withSingleTrace('Serializing value') {
            kryo.writeClassAndObject(output, value)
        }
    }

    void read(Kryo kryo, Input input) {
        dateEntered = input.readLong()
        loggerName = input.readString()
        withSingleTrace('Deserializing value') {
            value = (T) kryo.readClassAndObject(input)
        }
    }

    Logger getInstanceLog() {
        LoggerFactory.getLogger(loggerName)
    }

    private void withSingleTrace(String msg, Closure c) {
        Long start = currentTimeMillis()
        c()
        logTrace(msg, [_elapsedMs: currentTimeMillis() - start])
    }
}
