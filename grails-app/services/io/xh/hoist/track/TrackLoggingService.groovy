package io.xh.hoist.track

import io.xh.hoist.BaseService
import io.xh.hoist.config.ConfigService
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
class TrackLoggingService extends BaseService {

    ConfigService configService

    private Long DEBOUNCE_INTERVAL = 30 * SECONDS
    private PriorityBlockingQueue<TrackLogEntry> queue = new PriorityBlockingQueue<TrackLogEntry>()
    SimpleLogger orderedLog = new SimpleLogger(this.class.canonicalName + '.Log')

    void init() {
        createTimer(
            name: 'trackLogger',
            runFn: this.&drainQueue,
            interval: DEBOUNCE_INTERVAL
        )
        super.init()
    }

    void logEntry(Map rawEntry) {
        def entry = new TrackLogEntry(rawEntry)
        logInfo(entry.logData)
        queue.add(entry)
        drainQueue()
    }

    //-----------------
    // Implementation
    //-----------------
    private Map getConf() {
        return configService.getMap('xhActivityTrackingConfig')
    }

    synchronized private void drainQueue() {
        for (def e = queue.peek(); e && intervalElapsed(DEBOUNCE_INTERVAL, e.timestamp); e = queue.peek()) {
            def entry = queue.remove()
            try {
                orderedLog.logInfo(entry.logData)
            } catch (Throwable t) {
                logError('Failed to drain queue and log Track Entry.', t)
            }
        }
    }

    void clearCaches() {
        queue.clear()
    }

    //-------------------------------------------------
    // Internal Class to support sorting
    //-------------------------------------------------
    private class TrackLogEntry implements Comparable {
        Long timestamp
        Map logData

        TrackLogEntry(Map data) {
            timestamp = data.timestamp as Long
            logData = toLogData(data)
        }

        int compareTo(Object o) {
            timestamp <=> o.timestamp
        }

        private Map toLogData(Map entry) {
            // Log core info,
            String name = entry.username
            if (entry.impersonating) name += " (as ${entry.impersonating})"
            Map<String, Object> msgParts = [
                _timestamp    : new Date(entry.timestamp).format('yyyy-MM-dd HH:mm:ss.SSS'),
                _user         : name,
                _category     : entry.category,
                _msg          : entry.msg,
                _correlationId: entry.correlationId,
                _elapsedMs    : entry.elapsed,
            ].findAll { it.value != null } as Map<String, Object>

            // Log app data, if requested/configured.
            def data = entry.rawData,
                logData = entry.logData
            if (data && (data instanceof Map)) {
                logData = logData != null
                    ? logData
                    : conf.logData != null
                    ? conf.logData
                    : false

                if (logData) {
                    Map<String, Object> dataParts = data as Map<String, Object>
                    dataParts = dataParts.findAll { k, v ->
                        (logData === true || (logData as List).contains(k)) &&
                            !(v instanceof Map || v instanceof List)
                    }
                    msgParts.putAll(dataParts)
                }
            }
            msgParts
        }
    }
}
