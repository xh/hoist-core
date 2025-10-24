package io.xh.hoist.util;

import java.util.*;
import java.util.function.Function;

/**
 * Hoist tools for creating/manipulating collections in Java using groovy
 * like syntax.Useful for applications with large datasets and high performance
 * requirements.
 */
public class CollectionUtils {

    /**
     * Create an HashMap for a pre-determined number of items.
     * Will size appropriately to avoid any rehashing/re-allocation.
     */
    public static <K, V> HashMap<K, V> sizedHashMap(int size) {
        return new HashMap<K, V>((int) ((size / 0.75d) + 1d));
    }

    /**
     * Create a declarative hashmap from an alternating collection of key, value pairs
     */
    public static <K, V> HashMap<K, V> quickMap(Object... args) {
        HashMap<K, V> ret = sizedHashMap(args.length);
        for (int i = 0; i < args.length; i += 2) {
            ret.put((K) args[i], (V) args[i + 1]);
        }
        return ret;
    }

    /**
     * Java version of Groovy collectEntries
     */
    public static <S, K, V> Map<K, V> collectEntries(Collection<S> col, Function<S, Map.Entry<K, V>> mapper) {
        HashMap<K, V> ret = sizedHashMap(col.size());
        for (S val : col) {
            Map.Entry<K, V> e = mapper.apply(val);
            ret.put(e.getKey(), e.getValue());
        }
        return ret;
    }

    /**
     * Java version of Groovy collect
     */
    public static <S, T> List<T> collect(Collection<S> col, Function<S, T> mapper) {
        List<T> ret = new ArrayList<>(col.size());
        for (S val : col) {
            ret.add(mapper.apply(val));
        }
        return ret;
    }
}
