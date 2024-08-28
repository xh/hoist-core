/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.cache

import com.hazelcast.core.EntryEvent
import com.hazelcast.core.EntryListener
import com.hazelcast.replicatedmap.ReplicatedMap
import groovy.transform.CompileStatic
import io.xh.hoist.BaseService
import io.xh.hoist.cluster.ClusterService
import io.xh.hoist.util.Timer

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeoutException

import static io.xh.hoist.util.DateTimeUtils.MINUTES
import static io.xh.hoist.util.DateTimeUtils.SECONDS
import static io.xh.hoist.util.DateTimeUtils.intervalElapsed
import static java.lang.System.currentTimeMillis
import static java.util.Collections.emptyMap

@CompileStatic
class Cache<K,V> {

    /** Optional name for status logging disambiguation. */
    public final String name

    /** Service using this cache (for logging purposes). */
    public final BaseService svc

    /** Closure to determine if an entry should be expired. */
    public final Closure expireFn

    /** Time after which an entry should be expired, or closure to get this time. */
    public final Object expireTime

    /** Closure to determine the timestamp of an entry. */
    public final Closure timestampFn

    /** Whether this cache should be replicated across a cluster. */
    public final boolean replicate

    private final List<Closure> onChange = []
    private final Timer timer
    private final Map<K, Entry<V>> _map

    Cache(Map options) {
        name = (String) options.name
        svc = (BaseService) options.svc
        expireTime = options.expireTime
        expireFn = (Closure) options.expireFn
        timestampFn = (Closure) options.timestampFn
        replicate = (boolean) options.replicate && ClusterService.multiInstanceEnabled

        if (!svc) throw new RuntimeException("Missing required argument 'svc' for Cache")
        if (replicate && !name) {
            throw new RuntimeException("Cannot create a replicated cache without a unique name")
        }
        _map = replicate ? svc.getReplicatedMap(name) : new ConcurrentHashMap()

        if (replicate) {
            Closure onChg = {EntryEvent<K, Entry> it ->
                fireOnChange(it.key, it.oldValue?.value as V, it.value?.value as V)
            }
            def rMap = _map as ReplicatedMap<String, Object>,
                listener = [
                    entryAdded  : onChg,
                    entryUpdated: onChg,
                    entryRemoved: onChg,
                    entryEvicted: onChg,
                    entryExpired: onChg
                ] as EntryListener
            rMap.addEntryListener(listener, name)
        }

        timer = new Timer(
            owner: svc,
            primaryOnly: replicate,
            runFn: this.&cullEntries,
            interval: 15 * MINUTES,
            delay: true
        )
    }

    V get(K key) {
        return getEntry(key)?.value
    }

    Entry<V> getEntry(K key) {
        def ret = _map[key]
        if (ret && shouldExpire(ret)) {
            remove(key)
            return null
        }
        return ret
    }

    void remove(K key) {
        put(key, null)
    }

    void put(K key, V obj) {
        def oldEntry = _map[key]
        oldEntry?.isRemoving = true
        if (obj == null) {
            _map.remove(key)
        } else {
            _map.put(key, new Entry(key.toString(), obj, svc.instanceLog.name))
        }
        if (!replicate) fireOnChange(key, oldEntry?.value, obj)
    }

    V getOrCreate(K key, Closure<V> c) {
        V ret = get(key)
        if (ret == null) {
            ret = c(key)
            put(key, ret)
        }
        return ret
    }

    Map<K, V> getMap() {
        cullEntries()
        return (Map<K, V>) _map.collectEntries {k, v -> [k, v.value]}
    }

    int size() {
        return _map.size()
    }

    void clear() {
        _map.clear()
    }

    /**
     * Add a change handler to this object.
     *
     * @param handler.  A closure that receives a CacheValueChanged
     */
    void addChangeHandler(Closure handler) {
        onChange << handler
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
        Long timeout = (opts?.timeout ?: 30 * SECONDS) as Long,
             interval = (opts?.interval ?: 1 * SECONDS) as Long,
             startTime = currentTimeMillis()

        svc.withDebug("Waiting for cache entry value at ${key}") {
            do {
                if (getEntry(key)) return
                sleep(interval)
            } while (!intervalElapsed(timeout, startTime))

            String msg = opts?.timeoutMessage ?: "Timed out after ${timeout}ms waiting for replicated value '$key'"
            throw new TimeoutException(msg)
        }
    }

    //------------------------
    // Implementation
    //------------------------
    private void fireOnChange(K key, V oldValue, V newValue) {
        if (oldValue != newValue) {
            def change = new CacheValueChanged(key, oldValue, newValue)
            onChange.each { it.call(change) }
        }
    }

    private boolean shouldExpire(Entry<V> obj) {
        if (expireFn) return expireFn(obj)

        if (expireTime) {
            def timestamp
            if (timestampFn) {
                timestamp = timestampFn(obj.value)
                if (timestamp instanceof Date) timestamp = ((Date) timestamp).time
            } else {
                timestamp = obj.dateEntered
            }
            Long expire = (expireTime instanceof Closure ?  expireTime.call() : expireTime) as Long
            return intervalElapsed(expire, timestamp)
        }
        return false
    }

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
