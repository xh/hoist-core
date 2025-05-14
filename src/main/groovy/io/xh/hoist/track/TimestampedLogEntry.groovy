package io.xh.hoist.track

/**
 * Class to support sorting of data for logging.
 *
 * @internal
 */
class TimestampedLogEntry implements Comparable {
    TrackSeverity severity
    Map message
    Date timestamp

    int compareTo(Object o) {
        timestamp <=> o.timestamp
    }
}
