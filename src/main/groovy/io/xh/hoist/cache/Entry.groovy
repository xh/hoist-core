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
import groovy.transform.CompileStatic
import io.xh.hoist.log.LogSupport

import static java.lang.System.currentTimeMillis

@CompileStatic
class Entry<T> implements KryoSerializable, LogSupport {
    String key
    T value
    boolean isRemoving
    Long dateEntered

    Entry(String key, T value) {
        this.key = key
        this.value = value
        this.isRemoving = false
        this.dateEntered = System.currentTimeMillis()
    }

    Entry() {}

    void write(Kryo kryo, Output output) {
        output.writeBoolean(isRemoving)
        output.writeString(key)
        if (!isRemoving) {
            withSingleTrace('Serializing') {
                output.writeLong(dateEntered)
                kryo.writeClassAndObject(output, value)
            }
        }
    }

    void read(Kryo kryo, Input input) {
        isRemoving = input.readBoolean()
        key = input.readString()
        if (!isRemoving) {
            withSingleTrace('Deserializing') {
                dateEntered = input.readLong()
                value = kryo.readClassAndObject(input) as T
            }
        }
    }

    private void withSingleTrace(String msg, Closure c) {
        Long start = currentTimeMillis()
        c()
        logTrace(msg, key, [_elapsedMs: currentTimeMillis() - start])
    }
}
