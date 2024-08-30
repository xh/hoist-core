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
import static java.util.Collections.emptyMap

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
        @NamedParam boolean optimizeRemovals = false
    ) {
        super(svc, name, expireTime, expireFn, timestampFn, replicate, optimizeRemovals)

        if (replicate && !name) {
            throw new IllegalArgumentException("Cannot create a replicated Cache without a unique name")
        }

        // Intentional `this` below - super might override to false if !multiInstanceEnabled
        _map = this.replicate ? svc.getReplicatedMap(name) : new ConcurrentHashMap()

        if (_map instanceof ReplicatedMap) {
            _map.addEntryListener(getHzEntryListener())
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
        if (optimizeRemovals) oldEntry?.isOptimizedRemoval = true
        if (obj == null) {
            _map.remove(key)
        } else {
            _map.put(key, new Entry(key.toString(), obj, svc.instanceLog.name))
        }
        if (!replicate) fireOnChange(this, oldEntry?.value, obj)
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
        _map.clear()
    }


    /**
     * Wait for the cache entry to be populated.
     *
     * @param opts -- optional parameters governing behavior:
     *      timeout, time in ms to wait.  -1 to wait indefinitely (not recommended). Default 30 seconds.
     *      interval, time in ms to wait between tests.  Defaults to 1 second.
     *      timeoutMessage, custom message associated with any timeout.
     */
    void ensureAvailable(K key, Map opts = emptyMap()) {
        if (getEntry(key)) return

        svc.withDebug("Waiting for cache entry value at '$key'") {
            Long timeout = (opts?.timeout ?: 30 * SECONDS) as Long,
                 interval = (opts?.interval ?: 1 * SECONDS) as Long

            for (def startTime = currentTimeMillis(); !intervalElapsed(timeout, startTime); sleep(interval)) {
                if (getEntry(key)) return;
            }

            String msg = opts?.timeoutMessage ?: "Timed out after ${timeout}ms waiting for cached entry at '$key'"
            throw new TimeoutException(msg)
        }
    }

    //------------------------
    // Implementation
    //------------------------
    private void cullEntries() {
        Set<K> cullKeys = new HashSet<>()
        _map.each { k, v ->
            if (!v || shouldExpire(v)) cullKeys.add(k)
        }

        if (cullKeys.size()) {
            svc.logDebug("Cache '${name ?: "anon"}' culling ${cullKeys.size()} out of ${_map.size()} entries")
        }

        cullKeys.each {remove(it)}
    }
}
