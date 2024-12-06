package io.xh.hoist.cachedvalue

import com.hazelcast.config.InMemoryFormat
import com.hazelcast.topic.ITopic
import com.hazelcast.topic.Message
import com.hazelcast.topic.ReliableMessageListener
import groovy.transform.NamedParam
import groovy.transform.NamedVariant
import io.xh.hoist.BaseService
import io.xh.hoist.cluster.ClusterService
import io.xh.hoist.log.LogSupport
import io.xh.hoist.util.DateTimeUtils
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.util.concurrent.TimeoutException

import static grails.async.Promises.task
import static io.xh.hoist.util.DateTimeUtils.asEpochMilli
import static io.xh.hoist.util.DateTimeUtils.intervalElapsed
import static java.lang.System.currentTimeMillis

/**
 * Similar to {@link io.xh.hoist.cache.Cache}, but a single value that can be read, written, and expired.
 * Like Cache, this object supports replication across the cluster.
 */
class CachedValue<V> implements LogSupport {

    /** Service using this object. */
    public final BaseService svc

    /** Unique name in the context of the service associated with this object. */
    public final String name

    /**
     * Closure { CachedValueEntry<V> -> Boolean } to determine if an entry should
     * be expired (optional).
     */
    public final Closure<Boolean> expireFn

    /**
     * Entry TTL as epochMillis Long, or closure { void -> Long } to return the same (optional).
     * No effect if a custom expireFn is provided instead. If both null, entries will never expire.
     */
    public final Object expireTime

    /**
     * Closure { V -> Long | Date | Instant } to determine the timestamp of an entry (optional).
     * Must return a Long (as epochMillis), Date, or Instant.
     */
    public final Closure timestampFn

    /** True to replicate this cache across a cluster (default false). */
    public final boolean replicate

    /** Handler closures { CachedValueChanged<V> -> void } to be called on change. */
    public final List<Closure> onChange = []


    private final String loggerName
    private final ITopic<CachedValueEntry<V>> topic
    private CachedValueEntry<V> entry = new CachedValueEntry<V>(null, loggerName)


    /** @internal - do not construct directly - use {@link BaseService#createCachedValue}. */
    @NamedVariant
    CachedValue(
        @NamedParam(required = true) String name,
        @NamedParam(required = true) BaseService svc,
        @NamedParam Object expireTime = null,
        @NamedParam Closure expireFn = null,
        @NamedParam Closure timestampFn = null,
        @NamedParam Boolean replicate = false,
        @NamedParam Closure onChange = null
    ) {

        this.name = name
        this.svc = svc
        this.expireTime = expireTime
        this.expireFn = expireFn
        this.timestampFn = timestampFn
        this.replicate = replicate

        // Allow fine grain logging for this within namespace of owning service
        loggerName = "${svc.instanceLog.name}.CachedValue[$name]"

        topic = useCluster ? createUpdateTopic() : null
        if (onChange) {
            addChangeHandler(onChange)
        }
    }

    /** @returns the cached value. */
    V get() {
        if (shouldExpire(entry)) {
            set(null)
            return null
        }
        return entry.value
    }

    /** @returns the cached value, or calls the provided closure to create, cache, and return. */
    V getOrCreate(Closure<V> c) {
        V ret = entry.value
        if (ret == null || shouldExpire(entry)) {
            ret = c()
            set(ret)
        }
        return ret
    }

    /** Set the value. */
    void set(V value) {
        setInternal(new CachedValueEntry(value, loggerName), true)
    }

    /** Clear the value. */
    void clear() {
        set(null)
    }

    /** @returns timestamp of the current entry, or null if none. */
    Long getTimestamp() {
        getEntryTimestamp(entry)
    }

    /**
     * Wait for the replicated value to be populated.
     * @param timeout, time in ms to wait.  -1 to wait indefinitely (not recommended).
     * @param interval, time in ms to wait between tests.
     * @param timeoutMessage, custom message associated with any timeout.
     */
    @NamedVariant
    void ensureAvailable(
        @NamedParam Long timeout = 30 * DateTimeUtils.SECONDS,
        @NamedParam Long interval = 1 * DateTimeUtils.SECONDS,
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
    /** @param handler called on change with a {@link CachedValueChanged} object. */
    void addChangeHandler(Closure handler) {
        onChange << handler
    }

    //-------------------
    // Implementation
    //-------------------
    synchronized void setInternal(CachedValueEntry newEntry, boolean publish) {
        if (newEntry.uuid == entry.uuid) return

        // Make the swap and put on topic.
        def oldEntry = entry
        entry = newEntry
        if (publish && topic) topic.publish(newEntry)

        // Fire event handlers
        if (onChange && oldEntry.value !== newEntry.value) {
            task {
                def change = new CachedValueChanged(this, oldEntry.value, newEntry.value)
                onChange.each { it.call(change) }
            }
        }
    }

    boolean asBoolean() {
        return get() != null
    }

    private boolean shouldExpire(CachedValueEntry<V> entry) {
        if (entry.value == null) return false
        if (expireFn) return expireFn(entry)

        if (expireTime) {
            Long timestamp = getEntryTimestamp(entry),
                 expire = (expireTime instanceof Closure ? expireTime.call() : expireTime) as Long
            return intervalElapsed(expire, timestamp)
        }
        return false
    }

    private Long getEntryTimestamp(CachedValueEntry<V> entry) {
        return timestampFn ? asEpochMilli(timestampFn(entry.value)) : entry.dateEntered
    }

    private ITopic<CachedValueEntry<V>> createUpdateTopic() {
        // Create a durable topic with room for just a single item
        // and register for all events, including replay of event before this instance existed.
        def ret = ClusterService.hzInstance.getReliableTopic('xhcachedvalue.' + svc.hzName(name))
        ret.addMessageListener(
            new ReliableMessageListener<CachedValueEntry<V>>() {
                void onMessage(Message<CachedValueEntry<V>> message) {
                    def member = message.publishingMember,
                        src = member.localMember() ? '[self]' : member.getAttribute('instanceName')
                    logTrace("Received msg from $src", message.messageObject.uuid)
                    setInternal(message.messageObject, false)
                }

                long retrieveInitialSequence() { return 0 }

                void storeSequence(long sequence) {}

                boolean isLossTolerant() { return true }

                boolean isTerminal(Throwable e) {
                    svc.logError('Error handling update message', e)
                    return false
                }
            }
        )
        return ret
    }

    private boolean getUseCluster() {
        return replicate && ClusterService.multiInstanceEnabled
    }

    Logger getInstanceLog() {
        LoggerFactory.getLogger(loggerName)
    }


    Map getAdminStats() {
        def val = get(),
            ret = [
                name     : name,
                type     : 'CachedValue' + (replicate ? ' (replicated)' : ''),
                timestamp: timestamp
            ]
        if (val instanceof Collection || val instanceof Map) {
            ret.size = val.size()
        }
        return ret
    }

    List getComparisonFields() {
        if (!replicate) return null

        def val = get(),
            ret = ['timestamp']
        if (val instanceof Collection || val instanceof Map) {
            ret << 'size'
        }
        return ret
    }

}
