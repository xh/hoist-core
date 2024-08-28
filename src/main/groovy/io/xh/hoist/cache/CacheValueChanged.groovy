package io.xh.hoist.cache

class CacheValueChanged<K, V> {
    /**
     * Key of the value being changed.
     *
     * In the case of a CachedValue, will simply be the name of the CachedValue
     */
    final K key

    /** New Value.  Null if value being unset, or evicted. */
    final V value

    CacheValueChanged(K key, V value) {
        this.key = key
        this.value = value
    }
}
