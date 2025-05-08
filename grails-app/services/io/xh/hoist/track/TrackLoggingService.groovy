package io.xh.hoist.track

import groovy.transform.CompileStatic
import io.xh.hoist.BaseService
import io.xh.hoist.log.SimpleLogger

import java.util.concurrent.PriorityBlockingQueue

import static io.xh.hoist.util.DateTimeUtils.SECONDS
import static io.xh.hoist.util.DateTimeUtils.intervalElapsed

/**
 * Specialized support for writing activity tracking entries produced by {@link TrackService} to
 * both a dedicated log file and stdout.
 *
 * This service handles the fact that entries may arrive late and out-of-order due to client-
 * side debouncing. For the dedicated log, we use a buffer before insertion that allows us to
 * restore the sort order and use the timestamp property as the label for the log line.
 *
 * For the standard output log, we insert them immediately (with a secondary timestamp property) to
 * get them as close as possible to their actual occurrence time within the global flow.
 *
 * This class uses a PriorityBlockingQueue. We drain the queue on subsequent tracking statements,
 * AND timer to ensure timely and reliable logging/draining.
 *
 * @internal - not intended for direct use by applications.
 */
@CompileStatic
class TrackLoggingService extends BaseService {

    private Long DEBOUNCE_INTERVAL = 30 * SECONDS
    private PriorityBlockingQueue<TimestampedLogEntry> queue = new PriorityBlockingQueue<TimestampedLogEntry>()
    SimpleLogger orderedLog = new SimpleLogger(this.class.canonicalName + '.Log')

    void init() {
        createTimer(
            name: 'trackLogger',
            runFn: this.&drainQueue,
            interval: DEBOUNCE_INTERVAL
        )
        super.init()
    }

    void logEntry(TimestampedLogEntry entry) {
        logInfo(entry.message)
        queue.add(entry)
        drainQueue()
    }

    //-----------------
    // Implementation
    //-----------------
    private synchronized void drainQueue() {
        for (def e = queue.peek(); e && intervalElapsed(DEBOUNCE_INTERVAL, e.timestamp); e = queue.peek()) {
            def entry = queue.remove()
            try {
                orderedLog.logInfo(entry.message)
            } catch (Throwable t) {
                logError('Failed to log Track Entry.', t)
            }
        }
    }

    void clearCaches() {
        queue.clear()
    }

    Map getAdminStats() {[
        queue: queue.size()
    ]}
}
