/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.track

/**
 * Class to support sorting of data for logging.
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
