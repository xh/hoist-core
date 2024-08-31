/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.cache

import com.hazelcast.replicatedmap.ReplicatedMap
import groovy.transform.CompileStatic
import groovy.transform.NamedParam
import groovy.transform.NamedVariant
import io.xh.hoist.BaseService
import io.xh.hoist.util.Timer

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeoutException

import static io.xh.hoist.util.DateTimeUtils.MINUTES
import static io.xh.hoist.util.DateTimeUtils.SECONDS
import static io.xh.hoist.util.DateTimeUtils.intervalElapsed
import static java.lang.System.currentTimeMillis

@CompileStatic
class Cache<K,V> extends BaseCache<V> {

    private final Map<K, Entry<V>> _map
    private final Timer timer

    @NamedVariant
    Cache(
        @NamedParam(required = true) BaseService svc,
        @NamedParam String name,
        @NamedParam Object expireTime = null,
        @NamedParam Closure expireFn = null,
        @NamedParam Closure timestampFn = null,
        @NamedParam boolean replicate = false,
        @NamedParam boolean serializeOldValue = false,
        @NamedParam Closure onChange = null
    ) {
        super(svc, name, expireTime, expireFn, timestampFn, replicate, serializeOldValue, onChange)

        if (replicate && !name) {
            throw new IllegalArgumentException("Cannot create a replicated Cache without a unique name")
        }

        if (useCluster) {
            _map = svc.getReplicatedMap(name)
            (_map as ReplicatedMap).addEntryListener(getHzEntryListener())
        } else {
            _map = new ConcurrentHashMap()
        }

        timer = new Timer(
            owner: svc,
            primaryOnly: replicate,
            runFn: this.&cullEntries,
            interval: 15 * MINUTES,
            delay: true
        )
    }

    /** Get the value at key. */
    V get(K key) {
        return getEntry(key)?.value
    }

    /** Get the Entry at key. */
    Entry<V> getEntry(K key) {
        def ret = _map[key]
        if (ret && shouldExpire(ret)) {
            remove(key)
            return null
        }
        return ret
    }

    /** Remove the value at key.*/
    void remove(K key) {
        put(key, null)
    }

    /** Put a value at key. */
    void put(K key, V obj) {
        def oldEntry = _map[key]
        if (!serializeOldValue) oldEntry?.serializeValue = false
        if (obj == null) {
            _map.remove(key)
        } else {
            _map.put(key, new Entry(key.toString(), obj, svc.instanceLog.name))
        }
        if (!useCluster) fireOnChange(this, oldEntry?.value, obj)
    }

    /** @returns a cached value, or lazily creates if needed. */
    V getOrCreate(K key, Closure<V> c) {
        V ret = get(key)
        if (ret == null) {
            ret = c(key)
            put(key, ret)
        }
        return ret
    }

    /** @returns a map representation of currently cached data. */
    Map<K, V> getMap() {
        cullEntries()
        return (Map<K, V>) _map.collectEntries {k, v -> [k, v.value]}
    }

    /**
     * @returns the current size of the cache.
     * Note that this may include unexpired entries that have not yet been culled.
     */
    int size() {
        return _map.size()
    }

    /** Clear all entries */
    void clear() {
        // Remove key-wise to ensure that we get the proper removal message for each value and
        // work around exceptions with clear on replicated map.
        _map.each { k, v -> remove(k)}
    }

    /**
     * Wait for the cache entry to be populated.
     *
     * @param key, entry to check
     * @param timeout, time in ms to wait.  -1 to wait indefinitely (not recommended).
     * @param interval, time in ms to wait between tests.
     * @param timeoutMessage, custom message associated with any timeout.
     */
    @NamedVariant
    void ensureAvailable(
        K key,
        @NamedParam Long timeout = 30 * SECONDS,
        @NamedParam Long interval = 1 * SECONDS,
        @NamedParam String timeoutMessage = null
    ) {
        if (getEntry(key)) return

        svc.withDebug("Waiting for cache entry value at '$key'") {
            for (def startTime = currentTimeMillis(); !intervalElapsed(timeout, startTime); sleep(interval)) {
                if (getEntry(key)) return;
            }

            String msg = timeoutMessage ?: "Timed out after ${timeout}ms waiting for cached entry at '$key'"
            throw new TimeoutException(msg)
        }
    }

    //------------------------
    // Implementation
    //------------------------
    private void cullEntries() {
        Set<K> cullKeys = new HashSet<>()
        def oldSize = size()
        _map.each { k, v ->
            if (!v || shouldExpire(v)) {
                cullKeys.add(k)
                remove(k)
            }
        }

        if (cullKeys) {
            svc.logDebug("Cache '${name ?: "anon"}' culled ${cullKeys.size()} out of $oldSize entries")
        }
    }
}
