package io.xh.hoist.cache

import io.xh.hoist.BaseService
import io.xh.hoist.cluster.ClusterService

import java.util.concurrent.TimeoutException

import static io.xh.hoist.util.DateTimeUtils.SECONDS
import static io.xh.hoist.util.DateTimeUtils.intervalElapsed
import static java.lang.System.currentTimeMillis
import static java.util.Collections.emptyMap

/**
 * Similar to Cache, but a single value that can be read, written, and expired.
 *
 * Like Cache, this object supports replication across the cluster.
 */
class CachedValue<V> {

    /** Name of the cached value. */
    public final String name

    /** Service using this value  */
    public final BaseService svc

    /** Closure to determine if an entry should be expired. */
    public final Closure expireFn

    /** Time after which an entry should be expired. */
    public final Long expireTime

    /** Closure to determine the timestamp of an entry. */
    public final Closure timestampFn

    /** Whether this cache should be replicated across a cluster. */
    public final boolean replicate

    private final List<Closure> onChange = []
    private final Map<String, CacheEntry> _map

    CachedValue(Map options) {
        name = (String) options.name
        svc = (BaseService) options.svc
        expireTime = (Long) options.expireTime
        expireFn = (Closure) options.expireFn
        timestampFn = (Closure) options.timestampFn
        replicate = (boolean) options.replicate && ClusterService.multiInstanceEnabled

        if (!svc) throw new RuntimeException("Missing required argument 'svc' for Cache")

        _map = replicate ? svc.repValuesMap() : svc.simpleValuesMap()

        if (replicate) {
            def onChg = { fireOnChange(it.oldValue?.value, it.value?.value) }
            _map.addEntryListener([
                entryAdded  : onChg,
                entryUpdated: onChg,
                entryRemoved: onChg,
                entryEvicted: onChg,
                entryExpired: onChg
            ], name)
        }
    }

    /** Get the value. */
    V get() {
        def ret = _map[name]
        if (ret && shouldExpire(ret)) {
            set(null)
            return null
        }
        return ret?.value as V
    }

    /** Set the value. */
    void set(V value) {
        def oldEntry = _map[name]
        oldEntry?.isRemoving = true
        _map[name] = new CacheEntry(name, value, svc.instanceLog.name)

        if (!replicate) {
            this.fireOnChange(oldEntry?.value, value)
        }
    }

    /** Get the value, or create dynamically with closure and set it. */
    V getOrCreate(Closure<V> c) {
        V ret = get()
        if (!ret) {
            ret = c()
            set(ret)
        }
        return ret
    }


    /**
     * Add a change handler to this object.
     *
     * @param handler.  A closure that takes the old value and the new value
     */
    void addChangeHandler(Closure handler) {
        onChange << handler
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
        Long timeout = opts?.timeout ?: 30 * SECONDS,
            interval = opts?.interval ?: 1 * SECONDS,
            msg = opts?.timeoutMessage ?: "Timed out after ${timeout}ms waiting for replicated value '$name'",
            startTime = currentTimeMillis()

        svc.withDebug("Waiting for replicated value ${name}") {
            do {
                if (get()) return;
                sleep(interval)
            } while (!intervalElapsed(timeout, startTime))

            throw new TimeoutException(msg)
        }
    }

    //-----------------
    // Implementation
    //-----------------
    private void fireOnChange(V oldValue, V newValue) {
        if (oldValue != newValue) {
            onChange.each { it.call(oldValue, newValue) }
        }
    }

    private boolean shouldExpire(CacheEntry<V> obj) {
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
}
