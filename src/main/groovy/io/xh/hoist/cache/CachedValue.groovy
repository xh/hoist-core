package io.xh.hoist.cache

import com.hazelcast.replicatedmap.ReplicatedMap
import groovy.transform.NamedParam
import groovy.transform.NamedVariant
import io.xh.hoist.BaseService

import java.util.concurrent.TimeoutException

import static io.xh.hoist.util.DateTimeUtils.SECONDS
import static io.xh.hoist.util.DateTimeUtils.intervalElapsed
import static java.lang.System.currentTimeMillis
import static java.util.Collections.emptyMap


/**
 * Similar to {@link Cache}, but a single value that can be read, written, and expired.
 * Like Cache, this object supports replication across the cluster.
 */
class CachedValue<V> extends BaseCache<V> {

    private final Map<String, Entry<V>> _map

    @NamedVariant
    CachedValue(
        @NamedParam(required = true) BaseService svc,
        @NamedParam(required = true) String name,
        @NamedParam Object expireTime = null,
        @NamedParam Closure expireFn = null,
        @NamedParam Closure timestampFn = null,
        @NamedParam boolean replicate = false,
        @NamedParam boolean optimizeRemovals = true,
        @NamedParam Closure onChange = null
    ) {
        super(svc, name, expireTime, expireFn, timestampFn, replicate, optimizeRemovals, onChange)

        if (useCluster) {
            _map = svc.replicatedCachedValuesMap
            (_map as ReplicatedMap).addEntryListener(getHzEntryListener(), name)
        } else {
            _map = svc.localCachedValuesMap
        }
    }

    /** Get the value. */
    V get() {
        def ret = _map[name]
        if (ret && shouldExpire(ret)) {
            set(null)
            return null
        }
        return ret?.value
    }

    /** Set the value. */
    void set(V value) {
        def oldEntry = _map[name]
        if (optimizeRemovals) oldEntry?.isOptimizedRemoval = true
        if (value == null) {
            _map.remove(name)
        } else {
            _map[name] = new Entry(name, value, svc.instanceLog.name)
        }

        if (!useCluster) fireOnChange(name, oldEntry?.value, value)
    }

    /** Return the cached value, or lazily create if needed. */
    V getOrCreate(Closure<V> c) {
        V ret = get()
        if (ret == null) {
            ret = c()
            set(ret)
        }
        return ret
    }

    /** Clear value. */
    void clear() {
        set(null)
    }

    /**
     * Wait for the replicated value to be populated
     *
     * @param opts -- optional parameters governing behavior:
     *      timeout, time in ms to wait.  -1 to wait indefinitely (not recommended). Default 30 seconds.
     *      interval, time in ms to wait between tests.  Defaults to 1 second.
     *      timeoutMessage, custom message associated with any timeout.
     */
    void ensureAvailable(Map opts = emptyMap()) {
        if (get() != null) return

        svc.withDebug("Waiting for CachedValue '$name'") {
            Long timeout = (opts?.timeout ?: 30 * SECONDS) as Long,
                 interval = (opts?.interval ?: 1 * SECONDS) as Long

            for (def startTime = currentTimeMillis(); !intervalElapsed(timeout, startTime); sleep(interval)) {
                if (get() != null) return
            }

            String msg = opts?.timeoutMessage ?: "Timed out after ${timeout}ms waiting for CachedValue '$name'"
            throw new TimeoutException(msg)
        }
    }
}
