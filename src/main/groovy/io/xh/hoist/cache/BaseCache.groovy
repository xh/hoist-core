/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.cache


import groovy.transform.CompileStatic
import io.xh.hoist.BaseService
import io.xh.hoist.cluster.ClusterService

import static io.xh.hoist.util.DateTimeUtils.asEpochMilli
import static io.xh.hoist.util.DateTimeUtils.intervalElapsed

@CompileStatic
abstract class BaseCache<V> {

    /** Service using this object. */
    public final BaseService svc

    /** Unique name in the context of the service associated with this object. */
    public final String name

    /** Closure to determine if an entry should be expired (optional). */
    public final Closure<Boolean> expireFn

    /**
     * Entry TTL as epochMillis Long, or closure to return the same (optional).
     * No effect if a custom expireFn is provided instead. If both null, entries will never expire.
     */
    public final Object expireTime

    /**
     * Closure to determine the timestamp of an entry (optional).
     * Must return a Long (as epochMillis), Date, or Instant.
     */
    public final Closure timestampFn

    /** True to replicate this cache across a cluster (default false). */
    public final boolean replicate

    /**
     * True to serialize old values to replicas in `CacheValueChanged` events (default false).
     *
     * Not serializing old values improves performance and is especially important for caches
     * containing large objects that are expensive to serialize + deserialize. Enable only if your
     * event handlers need access to the previous value.
     */
    public final boolean serializeOldValue

    /** Handlers to be called on change with a {@link CacheValueChanged} object. */
    public final List<Closure> onChange = []

    BaseCache(
        String name,
        BaseService svc,
        Object expireTime,
        Closure expireFn,
        Closure timestampFn,
        boolean replicate,
        boolean serializeOldValue
    ) {
        this.name = name
        this.svc = svc
        this.expireTime = expireTime
        this.expireFn = expireFn
        this.timestampFn = timestampFn
        this.replicate = replicate
        this.serializeOldValue = serializeOldValue
    }

    /** @param handler called on change with a {@link CacheValueChanged} object. */
    abstract void addChangeHandler(Closure handler)

    /** Clear all values. */
    abstract void clear()

    /** True if this Cache should be stored across the cluster (backed by a ReplicatedMap). */
    boolean getUseCluster() {
        return replicate && ClusterService.multiInstanceEnabled
    }

    /** Information about this object, accessible via the Hoist Admin Console. */
    abstract Map getAdminStats()

    //------------------------
    // Implementation
    //------------------------
    protected void fireOnChange(Object key, V oldValue, V value) {
        def change = new CacheValueChanged(this, key, oldValue, value)
        onChange.each { it.call(change) }
    }

    protected boolean shouldExpire(Entry<V> entry) {
        if (expireFn) return expireFn(entry)

        if (expireTime) {
            Long timestamp = getEntryTimestamp(entry),
                expire = (expireTime instanceof Closure ?  expireTime.call() : expireTime) as Long
            return intervalElapsed(expire, timestamp)
        }
        return false
    }

    protected Long getEntryTimestamp(Entry<V> entry) {
        if (!entry) return null
        if (timestampFn) return asEpochMilli(timestampFn(entry.value))
        return entry.dateEntered
    }
}
