package io.xh.hoist.cluster

import groovy.transform.MapConstructor
import io.xh.hoist.json.JSONFormat

@MapConstructor
class ClusterConsistencyCheck implements JSONFormat {
    // Composite key of name and type
    String name
    String type

    /** Will be compared across instances. */
    Map<String, Object> checks
    /** Last time the checked object was updated. */
    Long timestamp

    Boolean test(ClusterConsistencyCheck other) {
        if (!sameObjectAs(other)) throw new RuntimeException("Cannot compare different objects: ${key} and ${other.key}.")
        return this.checks == other.checks
    }

    String getKey() {
        return "$name-$type"
    }

    boolean sameObjectAs(ClusterConsistencyCheck other) {
        return key == other.key
    }

    Map formatForJSON() {
        return [
            name: name,
            type: type,
            checks: checks,
            timestamp: timestamp
        ]
    }
}