package io.xh.hoist.cluster

import com.hazelcast.replicatedmap.ReplicatedMap

/**
 * A cluster available value that can be read and written by any node in the cluster.
 * Designed for small unrelated values that are needed across the cluster.
 *
 * This value will be stored in ths replicated map provided to this object.
 */
class ReplicatedValue<T> {

    final String key
    final private ReplicatedMap<String, Object> mp

    ReplicatedValue(String key, ReplicatedMap mp) {
        this.key = key
        this.mp = mp
    }

    T get() {
        mp[key] as T
    }

    void set(T value) {
        if (value == null) {
            mp.remove(key)
        }
        mp[key] = value
    }
}
