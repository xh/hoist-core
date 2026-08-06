/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.cache

class CacheEntryChanged<K, V> {

    /** Source object the changed value is contained within. */
    final Cache source

    /** Key of the entry being changed. */
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
     * Note that this property is *not* available for caches with serializeOldValue = false - the
     * default. In that case, this getter always returns null and logs a warning. Set
     * `serializeOldValue: true` on the Cache if your change handlers need the previous value.
     */
    V getOldValue() {
        if (!source.serializeOldValue) {
            source.logWarn('Accessing the old value for a cache with serializeOldValue=false')
            return null
        }
        return this._oldValue
    }
}
