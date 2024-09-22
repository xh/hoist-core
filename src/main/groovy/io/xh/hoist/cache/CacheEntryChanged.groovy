package io.xh.hoist.cache

class CacheEntryChanged<K, V> {

    /** Source object the changed value is contained within. */
    final Cache source

    /**
     * Key of the value being changed.
     *
     * When source of this change is a CachedValue, this key will simply be the name
     * of the CachedValue.
     */
    final K key

    private final V _oldValue
    private final V _value

    /** @internal */
    CacheEntryChanged(Cache source, K key, V oldValue, V value) {
        this.source = source
        this.key = key
        _oldValue = oldValue
        _value = value
    }

    /** New Value.  Null if value being unset or removed. */
    V getValue() {
        return this._value
    }

    /**
     * OldValue. Null if value being set for the first time.
     *
     * Note that this property is *not* available for caches with optimizeRemoval = true,
     * In that case, the value will always be null.
     */
    V getOldValue() {
        if (!source.serializeOldValue) {
            source.svc.logWarn('Accessing the old value for a cache with serializeOldValue=false')
            return null
        }
        return this._oldValue
    }
}
