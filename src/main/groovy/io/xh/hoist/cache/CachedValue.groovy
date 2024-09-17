package io.xh.hoist.cache

import com.hazelcast.replicatedmap.ReplicatedMap
import groovy.transform.NamedParam
import groovy.transform.NamedVariant
import io.xh.hoist.BaseService

import java.util.concurrent.TimeoutException

import static io.xh.hoist.util.DateTimeUtils.SECONDS
import static io.xh.hoist.util.DateTimeUtils.intervalElapsed
import static java.lang.System.currentTimeMillis

/**
 * Similar to {@link Cache}, but a single value that can be read, written, and expired.
 * Like Cache, this object supports replication across the cluster.
 */
class CachedValue<V> extends BaseCache<V> {

    private final Map<String, Entry<V>> _map

    /** @internal - do not construct directly - use {@link BaseService#createCachedValue}. */
    @NamedVariant
    CachedValue(
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

        _map = svc.getMapForCachedValue(this)
        if (onChange) addChangeHandler(onChange)
    }

    /** @returns the cached value. */
    V get() {
        def ret = _map[name]
        if (ret && shouldExpire(ret)) {
            set(null)
            return null
        }
        return ret?.value
    }

    /** @returns the cached value, or calls the provided closure to create, cache, and return. */
    V getOrCreate(Closure<V> c) {
        V ret = get()
        if (ret == null) {
            ret = c()
            set(ret)
        }
        return ret
    }

    /** Set the value. */
    void set(V value) {
        def oldEntry = _map[name]
        if (!serializeOldValue) oldEntry?.serializeValue = false
        if (value == null) {
            _map.remove(name)
        } else {
            _map[name] = new Entry(name, value, svc.instanceLog.name)
        }

        if (!useCluster) fireOnChange(name, oldEntry?.value, value)
    }

    /** Clear the value. */
    void clear() {
        set(null)
    }

    /** @returns timestamp of the current entry, or null if none. */
    Long getTimestamp() {
        getEntryTimestamp(_map[name])
    }

    /**
     * Wait for the replicated value to be populated.
     * @param timeout, time in ms to wait.  -1 to wait indefinitely (not recommended).
     * @param interval, time in ms to wait between tests.
     * @param timeoutMessage, custom message associated with any timeout.
     */
    @NamedVariant
    void ensureAvailable(
        @NamedParam Long timeout = 30 * SECONDS,
        @NamedParam Long interval = 1 * SECONDS,
        @NamedParam String timeoutMessage = null
    ) {
        if (get() != null) return

        svc.withDebug("Waiting for CachedValue '$name'") {
            for (def startTime = currentTimeMillis(); !intervalElapsed(timeout, startTime); sleep(interval)) {
                if (get() != null) return
            }

            String msg = timeoutMessage ?: "Timed out after ${timeout}ms waiting for CachedValue '$name'"
            throw new TimeoutException(msg)
        }
    }

    void addChangeHandler(Closure handler) {
        if (!onChange && _map instanceof ReplicatedMap) {
            _map.addEntryListener(new HzEntryListener(this), name)
        }
        onChange << handler
    }

    //-------------------
    // Implementation
    //-------------------
    Map getAdminStats() {
        def val = get(),
            ret = [
                name     : name,
                type     : 'CachedValue' + (replicate ? ' (replicated)' : ''),
                timestamp: timestamp
            ]
        if (val instanceof Collection) {
            ret.size = val.size()
        }
        return ret
    }

    boolean asBoolean() {
        return get() != null
    }
}
