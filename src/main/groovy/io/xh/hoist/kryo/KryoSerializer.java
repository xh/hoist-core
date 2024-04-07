package io.xh.hoist.kryo;


import com.esotericsoftware.kryo.ClassResolver;
import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.ReferenceResolver;
import com.esotericsoftware.kryo.io.InputChunked;
import com.esotericsoftware.kryo.io.OutputChunked;
import com.esotericsoftware.kryo.util.DefaultClassResolver;
import com.esotericsoftware.kryo.util.DefaultInstantiatorStrategy;
import com.esotericsoftware.kryo.util.MapReferenceResolver;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.HazelcastInstanceAware;
import com.hazelcast.internal.nio.ClassLoaderUtil;
import com.hazelcast.nio.ObjectDataOutput;
import com.hazelcast.nio.serialization.StreamSerializer;
import com.hazelcast.nio.ObjectDataInput;
import org.objenesis.strategy.StdInstantiatorStrategy;

import java.io.InputStream;
import java.io.OutputStream;

import static java.lang.ThreadLocal.withInitial;

/**
 * A Hazelcast Serializer that uses Kryo.
 *
 * Simplification of strategy from https://github.com/jerrinot/subzero
 */
class KryoSerializer<T> implements StreamSerializer<T>, HazelcastInstanceAware {

    private int typeId;
    private HazelcastInstance hzInstance;
    private final ThreadLocal<KryoContext> ctx = withInitial(() -> new KryoContext(hzInstance));

    //-----------------------------------
    // Hazelcast Overrides for Serializer
    //-----------------------------------
    public int getTypeId() {
        return typeId;
    }

    public void destroy() {
        KryoIdGenerator.instanceDestroyed(hzInstance);
    }

    public void setHazelcastInstance(HazelcastInstance instance) {
        hzInstance = instance;
        typeId = KryoIdGenerator.globalId(hzInstance);
    }

    public void write(ObjectDataOutput out, T object) {
        KryoContext kryoContext = ctx.get();
        OutputChunked output = kryoContext.outputChunked;
        output.setOutputStream((OutputStream) out);
        kryoContext.kryo.writeClassAndObject(output, object);
        output.endChunk();
        output.flush();
    }

    public T read(ObjectDataInput in) {
        KryoContext kryoContext = ctx.get();
        InputChunked input = kryoContext.inputChunked;
        input.setInputStream((InputStream) in);
        return (T) kryoContext.kryo.readClassAndObject(input);
    }


    //-------------------------
    // Implementation
    //--------------------------
    private static class KryoContext {
        final Kryo kryo;
        final InputChunked inputChunked = new InputChunked(16*1024);
        final OutputChunked outputChunked = new OutputChunked(16*1024);

        KryoContext(HazelcastInstance hzInstance) {
            ClassResolver classResolver = new HzClassResolver(hzInstance);
            ReferenceResolver referenceResolver = new MapReferenceResolver();

            Kryo kryo = new Kryo(classResolver, referenceResolver);
            kryo.setInstantiatorStrategy(new DefaultInstantiatorStrategy(new StdInstantiatorStrategy()));
            kryo.setRegistrationRequired(false);
            this.kryo = kryo;
        }
    }

    private static class HzClassResolver extends DefaultClassResolver {

        private final ClassLoader classLoader;

        public HzClassResolver(HazelcastInstance hzInstance) {
            classLoader = hzInstance.getConfig().getClassLoader();
        }

        protected Class<?> getTypeByName(String className) {
            try {
                return ClassLoaderUtil.loadClass(classLoader, className);
            } catch (ClassNotFoundException e) {
                return null;
            }
        }
    }
}



