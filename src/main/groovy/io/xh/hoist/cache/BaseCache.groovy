/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.cache

import com.hazelcast.core.EntryEvent
import com.hazelcast.core.EntryListener
import groovy.transform.CompileStatic
import io.xh.hoist.BaseService
import io.xh.hoist.cluster.ClusterService

import static io.xh.hoist.util.DateTimeUtils.*

@CompileStatic
abstract class BaseCache<V> {

    /** Service using this object. */
    public final BaseService svc

    /** Name.  Should be unique in the context of the service associated with this object. */
    public final String name

    /** Closure to determine if an entry should be expired. (optional) */
    public final Closure expireFn

    /** Time after which an entry should be expired, or closure to get this time. (optional) */
    public final Object expireTime

    /** Closure to determine the timestamp of an entry. (optional) */
    public final Closure timestampFn

    /** Whether this cache should be replicated across a cluster. Default false */
    public final boolean replicate

    /**
     * Optimize removals of replicated entries, such that the old value is
     * not re-serialized. Default false.
     *
     * This optimization is useful for caches containing large objects requiring
     * time to to serialize/deserialize.  NOTE -- if enabled, the CacheValueChanged
     * events fired on this object will *not* contain the oldValue.
     */
    public final boolean optimizeRemovals

    protected final List<Closure> onChange = []

    BaseCache(Map options) {
        name = (String) options.name
        svc = (BaseService) options.svc
        expireTime = options.expireTime
        expireFn = (Closure) options.expireFn
        timestampFn = (Closure) options.timestampFn
        replicate = (boolean) options.replicate && ClusterService.multiInstanceEnabled
        optimizeRemovals = (boolean) options.optimizeRemovals

        if (!svc) throw new RuntimeException("Missing required argument 'svc' for BaseCache")
    }

    /**
     * Add a change handler to this object.
     *
     * @param handler.  A closure that receives a CacheValueChanged
     */
    void addChangeHandler(Closure handler) {
        onChange << handler
    }


    //------------------------
    // Implementation
    //------------------------
    protected EntryListener getHzEntryListener() {
        Closure onChg = { EntryEvent<?, Entry<V>> it ->
            fireOnChange(it.key, it.oldValue?.value, it.value?.value)
        }
        return [
            entryAdded  : onChg,
            entryUpdated: onChg,
            entryRemoved: onChg,
            entryEvicted: onChg,
            entryExpired: onChg
        ] as EntryListener
    }

    protected void fireOnChange(Object key, V oldValue, V value) {
        def change = new CacheValueChanged(this, key, oldValue,  value)
        onChange.each { it.call(change) }
    }

    protected boolean shouldExpire(Entry<V> obj) {
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
