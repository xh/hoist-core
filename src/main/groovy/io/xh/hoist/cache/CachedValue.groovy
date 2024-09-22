package io.xh.hoist.cache

import antlr.debug.MessageListener
import com.hazelcast.ringbuffer.Ringbuffer
import com.hazelcast.topic.ITopic
import com.hazelcast.topic.Message
import com.hazelcast.topic.ReliableMessageListener
import groovy.transform.NamedParam
import groovy.transform.NamedVariant
import io.xh.hoist.BaseService
import io.xh.hoist.cluster.ClusterService
import io.xh.hoist.log.LogSupport
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.util.concurrent.TimeoutException

import static io.xh.hoist.util.DateTimeUtils.SECONDS
import static io.xh.hoist.util.DateTimeUtils.asEpochMilli
import static io.xh.hoist.util.DateTimeUtils.intervalElapsed
import static java.lang.System.currentTimeMillis

/**
 * Similar to {@link Cache}, but a single value that can be read, written, and expired.
 * Like Cache, this object supports replication across the cluster.
 */
class CachedValue<V> extends BaseCache<V> implements LogSupport {

    private final ITopic<CachedValueEntry<V>> topic
    private final Ringbuffer<CachedValueEntry<V>> ring
    private final String loggerName
    private CachedValueEntry<V> entry

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
        super(name, svc, expireTime, expireFn, timestampFn, replicate, true)
        loggerName = svc.instanceLog.name + '_' + name

        if (useCluster) {
            topic = createUpdateTopic()
        }
        if (onChange) addChangeHandler(onChange)
    }

    /** @returns the cached value. */
    V get() {
        if (shouldExpire(entry)) {
            set(null)
            return null
        }
        return entry?.value
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
        if (value == entry?.value) return
        setInternal(new CachedValueEntry(value, loggerName))
        topic?.publish(entry)
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
        onChange << handler
    }

    //-------------------
    // Implementation
    //-------------------
    void setInternal(CachedValueEntry newEntry) {
        def oldEntry = entry
        entry = newEntry
        def change = new CachedValueChanged(this, oldEntry?.value, newEntry?.value)
        onChange.each { it.call(change) }
    }

    boolean asBoolean() {
        return get() != null
    }

    private boolean shouldExpire(CachedValueEntry<V> entry) {
        if (!entry) return null
        if (expireFn) return expireFn(entry)

        if (expireTime) {
            Long timestamp = getEntryTimestamp(entry),
                 expire = (expireTime instanceof Closure ? expireTime.call() : expireTime) as Long
            return intervalElapsed(expire, timestamp)
        }
        return false
    }

    private Long getEntryTimestamp(CachedValueEntry<V> entry) {
        if (!entry) return null
        if (timestampFn) return asEpochMilli(timestampFn(entry.value))
        return entry.dateEntered
    }

    private ITopic<CachedValueEntry<V>> createUpdateTopic() {
        // 0) Create a durable topic with room for just a single item
        def hzInstance = ClusterService.hzInstance,
            hzConfig = hzInstance.config,
            hzName = svc.hzName("xh_$name")
        hzConfig.getRingbufferConfig(hzName).with { capacity = 1 }
        hzConfig.getReliableTopicConfig(hzName).with { readBatchSize = 1 }

        // 1) ...rnd register for all events, including replay of event before this instance existed.
        def ret = hzInstance.getReliableTopic(hzName)
        ret.addMessageListener(
            new ReliableMessageListener<CachedValueEntry<V>>() {
                void onMessage(Message<CachedValueEntry<V>> message) {
                    def publisher = message.publishingMember
                    svc.logDebug('Received update', [source: publisher.getAttribute('instanceName')])
                    if (publisher.localMember()) return
                    setInternal((CachedValueEntry) message.messageObject)
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

    Logger getInstanceLog() {
        LoggerFactory.getLogger(loggerName)
    }
}
