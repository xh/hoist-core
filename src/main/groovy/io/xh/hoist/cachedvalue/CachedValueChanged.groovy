package io.xh.hoist.cachedvalue

class CachedValueChanged<V> {

    /** Source object the changed value is contained within. */
    final CachedValue source

    /** OldValue. Null if value being set for the first time.*/
    final V oldValue

    /** New Value.  Null if value being unset or removed. */
    final V value

    /** @internal */
    CachedValueChanged(CachedValue source, V oldValue, V value) {
        this.source = source
        this.oldValue = oldValue
        this.value = value
    }
}
