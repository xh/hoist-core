/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2020 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.cache

import groovy.transform.CompileStatic
import io.xh.hoist.BaseService

import java.util.concurrent.ConcurrentHashMap

import static io.xh.hoist.util.DateTimeUtils.MINUTES
import static io.xh.hoist.util.DateTimeUtils.intervalElapsed
import static java.lang.System.currentTimeMillis

@CompileStatic
class Cache<K,V> {

    private final ConcurrentHashMap<K, Entry<V>> _map = new ConcurrentHashMap()
    private Date _lastCull

    public final String name              //   name for status logging disambiguation [default anon]
    public final BaseService svc          //   service using this cache (for logging purposes)
    public final Closure expireFn
    public final Long expireTime
    public final Closure timestampFn

    Cache(Map options) {
        name = (String) options.name ?: 'anon'
        svc = (BaseService) options.svc
        expireTime = (Long) options.expireTime
        expireFn = (Closure) options.expireFn
        timestampFn = (Closure) options.timestampFn

        if (!svc) throw new RuntimeException("Missing required argument 'svc' for Cache")
    }

    V get(K key) {
        return getEntry(key)?.value
    }

    Entry<V> getEntry(K key) {
        cullEntries()
        def ret = _map[key]
        if (ret && shouldExpire(ret)) {
            _map.remove(key)
            return null
        }
        return ret
    }

    void put(K key, V obj) {
        cullEntries()
        _map.put(key, new Entry(obj))
    }

    V getOrCreate(K key, Closure c) {
        V ret = get(key)
        if (!ret) {
            ret = (V) c(key)
            put(key, ret)
        }
        return ret
    }

    Map<K, V> getMap() {
        cullEntries(true)
        return (Map<K, V>) _map.collectEntries {k, v -> [k, v.value]}
    }

    int size() {
        return _map.size()
    }

    void clear() {
        _map.clear()
    }

    
    //------------------------
    // Implementation
    //------------------------
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
            return currentTimeMillis() - ((Long) timestamp) > expireTime
        }
        return false
    }

    private void cullEntries(force = false) {
        if (force || intervalElapsed(15 * MINUTES, _lastCull)) {
            _lastCull = new Date()
            Set cullKeys = new HashSet()
            _map.each {k, v ->
                if (shouldExpire(v)) cullKeys.add(k)
            }

            if (cullKeys.size()) {
                svc.instanceLog.debug("Cache '$name' culling ${cullKeys.size()} out of ${_map.size()} entries")
            }

            cullKeys.each {_map.remove(it)}
        }
    }
    
}
