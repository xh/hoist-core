/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2025 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.cache

import com.hazelcast.core.EntryEvent
import com.hazelcast.core.EntryListener
import com.hazelcast.map.MapEvent

/** @internal */
class CacheEntryListener implements EntryListener {

    private Cache target

    CacheEntryListener(Cache target) {
        this.target = target
    }

    void entryAdded(EntryEvent event) {
        fireEvent(event)
    }

    void entryEvicted(EntryEvent event) {
        fireEvent(event)
    }

    void entryExpired(EntryEvent event) {
        fireEvent(event)
    }

    void entryRemoved(EntryEvent event) {
        fireEvent(event)
    }

    void entryUpdated(EntryEvent event) {
        fireEvent(event)
    }

    void mapCleared(MapEvent event) {}

    void mapEvicted(MapEvent event) {}

    private fireEvent(EntryEvent event) {
        target.fireOnChange(event.key, event.oldValue?.value, event.value?.value)
    }

}
