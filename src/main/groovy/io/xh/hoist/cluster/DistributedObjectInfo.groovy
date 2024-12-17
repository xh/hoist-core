package io.xh.hoist.cluster

import io.xh.hoist.json.JSONFormat

import static io.xh.hoist.util.Utils.getClusterService

class DistributedObjectInfo implements JSONFormat {
    // Absolute name of the object, make sure to use `svc.hzName(name)` on relative-named objects
    String name
    // Type of object
    String type
    // Admin stats of the object
    Map adminStats
    // Admin stat fields to compare, if any
    List<String> comparisonFields
    // Name of the cluster instance this data was collected from
    String instanceName
    // Error encountered while collecting this object's data
    String error

    DistributedObjectInfo(Map args) {
        adminStats = (args.adminStats ?: Collections.emptyMap()) as Map
        comparisonFields = (args.comparisonFields ?: Collections.emptyList()) as List<String>
        instanceName = clusterService.localName
        name = args.name ?: adminStats.name
        type = args.type ?: adminStats.type
        error = args.error
    }

    boolean isMatching(DistributedObjectInfo other) {
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
            instanceName: instanceName,
            comparisonFields: comparisonFields,
            adminStats: adminStats
        ]
    }
}