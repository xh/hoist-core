package io.xh.hoist.cache

import com.hazelcast.core.EntryListener
import com.hazelcast.replicatedmap.ReplicatedMap
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

    /** Time after which an entry should be expired or closure to return same */
    public final Object expireTime

    /** Closure to determine the timestamp of an entry. */
    public final Closure timestampFn

    /** Whether this cache should be replicated across a cluster. */
    public final boolean replicate

    private final List<Closure> onChange = []
    private final Map<String, Entry> _map

    CachedValue(Map options) {
        name = (String) options.name
        svc = (BaseService) options.svc
        expireTime = options.expireTime
        expireFn = (Closure) options.expireFn
        timestampFn = (Closure) options.timestampFn
        replicate = (boolean) options.replicate && ClusterService.multiInstanceEnabled

        if (!svc) throw new RuntimeException("Missing required argument 'svc' for Cache")

        _map = replicate ? svc.repValuesMap : svc.simpleValuesMap

        if (replicate) {
            def rMap = _map as ReplicatedMap<String, Object>,
                onChg = {fireOnChange(it.value?.value)},
                listener = [
                    entryAdded  : onChg,
                    entryUpdated: onChg,
                    entryRemoved: onChg,
                    entryEvicted: onChg,
                    entryExpired: onChg
                ] as EntryListener
            rMap.addEntryListener(listener, name)
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
        if (value == null) {
            _map.remove(name)
        } else {
            _map[name] = new Entry(name, value, svc.instanceLog.name)
        }

        if (!replicate) {
            this.fireOnChange(value)
        }
    }

    /** Get the value, or create dynamically with closure and set it. */
    V getOrCreate(Closure<V> c) {
        V ret = get()
        if (ret == null) {
            ret = c()
            set(ret)
        }
        return ret
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

    //-----------------
    // Implementation
    //-----------------
    private void fireOnChange(V value) {
        def change = new CacheValueChanged(name, value)
        onChange.each { it.call(change) }
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
}
