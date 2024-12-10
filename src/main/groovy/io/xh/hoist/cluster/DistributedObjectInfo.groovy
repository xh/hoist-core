package io.xh.hoist.cluster

import io.xh.hoist.json.JSONFormat

import static io.xh.hoist.util.Utils.getClusterService

class DistributedObjectInfo implements JSONFormat {
    // Absolute name of the object, use `svc.hzName(name)` on relative-named objects
    String name
    // Absolute name of the parent
    String parentName
    // Admin stats of the object
    Map adminStats
    // Admin stat fields to compare, if any
    List<String> comparisonFields
    // Name of the cluster instance this data was collected from
    String instanceName

    String getType() { adminStats.type }

    DistributedObjectInfo(Map args) {
        name = args.name
        adminStats = args.adminStats as Map
        comparisonFields = args.comparisonFields as List<String>
        parentName = args.parentName
        instanceName = clusterService.localName
    }

    boolean isMatching(DistributedObjectInfo other) {
        if (!this.isComparableWith(other)) throw new RuntimeException("Cannot compare different objects: ${this.name} and ${other.name}.")

        return this.comparisonFields == other.comparisonFields && this.comparisonFields.every { field ->
            this.adminStats[field] == other.adminStats[field]
        }
    }

    boolean isComparableWith(DistributedObjectInfo other) {
        return this.name == other.name
    }

    Map formatForJSON() {
        return [
            name: name,
            type: type,
            parentName: parentName,
            instanceName: instanceName,
            comparisonFields: comparisonFields,
            adminStats: adminStats
        ]
    }
}