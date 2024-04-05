package io.xh.hoist.cluster
/**
 * A cluster available value that can be read and written by any node in the cluster.
 * Designed for small unrelated values that are needed across the cluster.
 *
 * This value will be stored in ths replicated map provided to this object.
 */
class ReplicatedValue<T> {

    final String key
    final Map<String, ReplicatedValueEntry<T>> mp

    ReplicatedValue(String key, Map mp) {
        this.key = key
        this.mp = mp
    }

    T get() {
        mp[key]?.value as T
    }

    void set(T value) {
        mp[key]?.isRemoving = true

        value == null ?
            mp.remove(key) :
            mp.put(key, new ReplicatedValueEntry<T>(key, value))
    }
}
