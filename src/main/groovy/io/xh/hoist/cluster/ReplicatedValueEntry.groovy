package io.xh.hoist.cluster

import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.KryoSerializable
import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.io.Output
import groovy.transform.CompileStatic
import io.xh.hoist.log.LogSupport

@CompileStatic
class ReplicatedValueEntry<T> implements KryoSerializable, LogSupport {
    String key
    T value
    boolean isRemoving

    ReplicatedValueEntry() {}

    ReplicatedValueEntry(String key, T value) {
        this.key = key
        this.value = value
        this.isRemoving = false
    }

    void write(Kryo kryo, Output output) {
        output.writeBoolean(isRemoving)
        output.writeString(key)
        if (!isRemoving) {
            withTrace(['Serializing', key]) {
                kryo.writeClassAndObject(output, value)
            }
        }
    }

    void read(Kryo kryo, Input input) {
        isRemoving = input.readBoolean()
        key = input.readString()
        if (!isRemoving) {
            withTrace(['Deserializing', key]) {
                value = kryo.readClassAndObject(input) as T
            }
        }
    }
}
