package io.xh.hoist.util

import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.io.Output
import com.esotericsoftware.kryo.serializers.JavaSerializer
import io.xh.hoist.log.LogSupport

import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

import static java.lang.System.currentTimeMillis

class TestUtils {
    static testSerialization(Object obj, Class clazz, LogSupport logSupport, Map opts) {
        Kryo kryo = new Kryo()
        kryo.registrationRequired = false
        if (opts.java) {
            kryo.register(clazz, new JavaSerializer())
        }
        kryo.reset()
        kryo.setReferences(opts.refs)
        ByteArrayOutputStream byteStream = new ByteArrayOutputStream(32 * 1024)

        Long startTime = currentTimeMillis()
        OutputStream outputStream = byteStream
        if (opts.compress) outputStream = new DeflaterOutputStream(outputStream)
        Output output = new Output(outputStream)
        kryo.writeObject(output, obj);
        output.close()
        Long serializeTime = currentTimeMillis()

        InputStream inputStream = new ByteArrayInputStream(byteStream.toByteArray())
        if (opts.compress) inputStream = new InflaterInputStream(inputStream)
        Input input = new Input(inputStream)
        Object object2 = kryo.readObject(input, clazz)
        Long endTime = currentTimeMillis()

        logSupport.logInfo(
            opts,
            "(${serializeTime - startTime}/${endTime - serializeTime})ms",
            "${(byteStream.size() / 1000000).round(2)}MB",
            "${endTime - startTime}ms"
        )
    }
}
