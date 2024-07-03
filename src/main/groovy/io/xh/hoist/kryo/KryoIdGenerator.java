package io.xh.hoist.kryo;


import com.hazelcast.core.HazelcastInstance;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A thread safe generator for serializer ids.
 *
 * Simplification of class from https://github.com/jerrinot/subzero
 */
public class KryoIdGenerator {
    private static final int BASE_ID = 6000;

    private static ConcurrentHashMap<HazelcastInstance, IdSequence> counterMap = new ConcurrentHashMap<>();

    public static int globalId(HazelcastInstance hz) {
        IdSequence idSequence = getOrCreateSequence(hz);
        return idSequence.idFor(hz.getClass());
    }

    public static void instanceDestroyed(HazelcastInstance hz) {
        counterMap.remove(hz);
    }

    private static IdSequence getOrCreateSequence(HazelcastInstance hazelcastInstance) {
        IdSequence currentSequence = counterMap.get(hazelcastInstance);
        if (currentSequence != null) {
            return currentSequence;
        }
        IdSequence newSequence = new IdSequence();
        currentSequence = counterMap.putIfAbsent(hazelcastInstance, newSequence);
        return currentSequence == null ? newSequence : currentSequence;
    }

    private static class IdSequence {
        private final ConcurrentMap<Class<?>, Integer> knownTypes = new ConcurrentHashMap<>();
        private final AtomicInteger counter = new AtomicInteger(BASE_ID);

        private int idFor(Class<?> clazz) {
            return knownTypes.computeIfAbsent(clazz, (ignored) -> counter.incrementAndGet());
        }
    }
}
