package io.xh.hoist.cluster

import io.xh.hoist.json.JSONFormat

class ClusterConsistencyResult implements JSONFormat {
    // Composite key of name and type
    String name
    String type

    String owner

    /** fieldName -> instanceName -> fieldValue */
    Map<String, Map<String, Object>> checks = [:]
    /** instanceName -> timestamp */
    Map<String, Long> lastUpdated = [:]

    ClusterConsistencyResult(ClusterConsistencyCheck first, String instanceName) {
        name = first.name
        type = first.type
        owner = first.owner
        add(first, instanceName)
    }

    void add(ClusterConsistencyCheck other, String instanceName) {
        if (!sameObjectAs(other)) throw new RuntimeException("Cannot compare different objects: ${key} and ${other.key}.")

        def otherChecks = other.checks ?: [:]

        // Merge fields already in checks
        checks.each { fieldName, valuesByInstance ->
            if (otherChecks.containsKey(fieldName)) {
                valuesByInstance[instanceName] = otherChecks[fieldName]
            }
        }
        // Add in fields not already in checks
        (otherChecks.keySet() - checks.keySet()).each { fieldName ->
            checks[fieldName] = [(instanceName): otherChecks[fieldName]]
        }

        // Merge lastUpdated map
        if (other.lastUpdated) {
            lastUpdated[instanceName] = other.lastUpdated
        }
    }

    Boolean hasInconsistency() {
        checks.any { fieldName, valuesByInstance ->
            def values = valuesByInstance.values()
            values.any { v1 -> values.any { v2 -> v1 != v2} }
        }
    }

    String getKey() {
        return "$name-$type"
    }

    boolean sameObjectAs(ClusterConsistencyCheck other) {
        return key == other.key
    }

    Map formatForJSON() {
        return [
            id              : key,
            name            : name,
            type            : type,
            owner           : owner,
            hasInconsistency: hasInconsistency(),
            checks          : checks,
            lastUpdated     : lastUpdated
        ]
    }
}