package io.xh.hoist.cluster

import groovy.transform.MapConstructor
import io.xh.hoist.json.JSONFormat

import static io.xh.hoist.util.Utils.getClusterService

@MapConstructor
class DistributedObjectInfo implements JSONFormat {

    final Map adminStats
    final List<String> comparisonFields
    final String instanceName = clusterService.localName
    final String owner
    final String name

    String getType() { adminStats.type }

    // Composite key of name and type
    String getKey() { "$name-$type" }

    Boolean isMatching(DistributedObjectInfo other) {
        if (!isComparableWith(other)) throw new RuntimeException("Cannot compare different objects: ${key} and ${other.key}.")

        return this.comparisonFields == other.comparisonFields && this.comparisonFields.every { myField ->
            this.adminStats[myField] == other.adminStats[myField]
        }
    }

    boolean isComparableWith(DistributedObjectInfo other) {
        return key == other.key
    }

    Map formatForJSON() {
        return [
            id: key,
            name: name,
            type: type,
            owner: owner,
            instanceName: instanceName,
            comparisonFields: comparisonFields,
            adminStats: adminStats
        ]
    }
}