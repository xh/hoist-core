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
import java.util.concurrent.TimeoutException

import static io.xh.hoist.util.DateTimeUtils.MINUTES
import static io.xh.hoist.util.DateTimeUtils.SECONDS
import static io.xh.hoist.util.DateTimeUtils.intervalElapsed
import static java.lang.System.currentTimeMillis

/**
 * A key-value Cache, with support for optional entry TTL and replication across a cluster.
 */
@CompileStatic
class Cache<K, V> extends BaseCache<V> {

    private final Map<K, Entry<V>> _map
    private final Timer cullTimer

    /** @internal - do not construct directly - use {@link BaseService#createCache}.  */
    @NamedVariant
    Cache(
        @NamedParam(required = true) String name,
        @NamedParam(required = true) BaseService svc,
        @NamedParam Object expireTime = null,
        @NamedParam Closure expireFn = null,
        @NamedParam Closure timestampFn = null,
        @NamedParam Boolean replicate = false,
        @NamedParam Boolean serializeOldValue = false,
        @NamedParam Closure onChange = null
    ) {
        super(name, svc, expireTime, expireFn, timestampFn, replicate, serializeOldValue)

        _map = svc.getMapForCache(this)
        if (onChange) addChangeHandler(onChange)

        cullTimer = svc.createTimer(
            name: "xh_${name}_cullEntries",
            runFn: this.&cullEntries,
            interval: 15 * MINUTES,
            delay: true,
            primaryOnly: useCluster
        )
    }

    /** @returns the cached value at key.  */
    V get(K key) {
        return getEntry(key)?.value
    }

    /** @returns the cached Entry at key.  */
    Entry<V> getEntry(K key) {
        def ret = _map[key]
        if (ret && shouldExpire(ret)) {
            remove(key)
            return null
        }
        return ret
    }

    /** Remove the value at key. */
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

    /** @returns cached value for key, or lazily creates if needed.  */
    V getOrCreate(K key, Closure<V> c) {
        V ret = get(key)
        if (ret == null) {
            ret = c(key)
            put(key, ret)
        }
        return ret
    }

    /** @returns a Map representation of currently cached data.  */
    Map<K, V> getMap() {
        cullEntries()
        return (Map<K, V>) _map.collectEntries { k, v -> [k, v.value] }
    }

    /** @returns the timestamp of the cached Entry at key.  */
    Long getTimestamp(K key) {
        return getEntryTimestamp(_map[key])
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
        _map.each { k, v -> remove(k) }
    }

    void addChangeHandler(Closure handler) {
        if (!onChange && _map instanceof ReplicatedMap) {
            _map.addEntryListener(new HzEntryListener(this))
        }
        onChange << handler
    }


    /**
     * Wait for the cache entry to be populated.
     * @param key - entry to check
     * @param timeout - time in ms to wait.  -1 to wait indefinitely (not recommended).
     * @param interval - time in ms to wait between tests.
     * @param timeoutMessage - custom message associated with any timeout.
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

    Map getAdminStats() {
        [
            name           : name,
            type           : 'Cache' + (replicate ? ' (replicated)' : ''),
            count          : size(),
            latestTimestamp: _map.max { it.value.dateEntered }?.value?.dateEntered,
            lastCullTime   : cullTimer.lastRunCompleted
        ]
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
            svc.logDebug("Cache '$name' culled ${cullKeys.size()} out of $oldSize entries")
        }
    }
}
