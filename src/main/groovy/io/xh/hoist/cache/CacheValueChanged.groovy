package io.xh.hoist.cache

class CacheValueChanged<K, V> {
    /**
     * Key of the value being changed.
     *
     * In the case of a CachedValue, will simply be the name of the CachedValue
     */
    final K key

    /** Old Value.  Null if key being set for the first time. */
    final V oldValue

    /** New Value.  Null if value being unset, or evicted. */
    final V newValue

    CacheValueChanged(K key, V oldValue, V newValue) {
        this.key = key
        this.oldValue = oldValue
        this.newValue = newValue
    }
}
