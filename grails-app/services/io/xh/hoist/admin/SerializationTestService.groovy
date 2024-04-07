/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin

import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.KryoSerializable
import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.io.Output
import io.xh.hoist.BaseService
import io.xh.hoist.log.LogSupport
import io.xh.hoist.pref.PrefService

import static java.lang.System.currentTimeMillis

class SerializationTestService extends BaseService {

    private replicatedValue = getReplicatedValue('sniff')
    PrefService prefService

    void init() {
        replicatedValue.set(new TestObject([foo: 'foo', bar: new Date(), baz: [submap:'hi'], biz: null]))
        prefService.setBool('darkMode', true)
        logInfo(replicatedValue.get().toString())
    }
}

class TestObject implements Serializable, LogSupport, KryoSerializable {
    private String foo
    private Date bar
    private Map baz
    private Object biz

    void write(Kryo kryo, Output output) {
        withSingleTrace('Serializing - Hi') {
            output.writeString(foo)
            kryo.writeObjectOrNull(output, bar, Date)
            kryo.writeClassAndObject(output, baz)
            kryo.writeClassAndObject(output, biz)
        }
    }

    void read(Kryo kryo, Input input) {
        withSingleTrace('Deserializing  - Hi!') {
            foo = input.readString()
            bar = kryo.readObject(input, Date)
            baz = kryo.readClassAndObject(input) as Map
            biz = kryo.readClassAndObject(input)

        }
    }

    private void withSingleTrace(String msg, Closure c) {
        Long start = currentTimeMillis()
        c()
        logTrace(msg, [_elapsedMs: currentTimeMillis() - start])
    }
}