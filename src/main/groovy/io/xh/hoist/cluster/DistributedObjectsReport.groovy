package io.xh.hoist.cluster

import groovy.transform.MapConstructor
import io.xh.hoist.json.JSONFormat

class DistributedObjectsReport implements JSONFormat {

    // List of all of the distributed object data from all of the instances in the cluster.
    List<DistributedObjectInfo> info
    // Roughly when this report was generated, how long it took.
    Long startTimestamp
    Long endTimestamp
    // Map of mismatches
    Map<String, List<List<String>>> breaks

    DistributedObjectsReport(Map args) {
        info = args.info as List<DistributedObjectInfo>
        startTimestamp = args.startTimestamp as Long
        endTimestamp = args.endTimestamp as Long

        breaks = createBreaks()
    }

    Map formatForJSON() {
        return [
            info              : info,
            breaks            : breaks,
            startTimestamp    : startTimestamp,
            endTimestamp      : endTimestamp
        ]
    }

    private Map<String, List<List<String>>> createBreaks() {
        Map<String, List<List<String>>> breaks = [:].withDefault { [] }
        info.groupBy { it.name }.each { name, infoObjs ->
            [infoObjs, infoObjs].eachCombination { a, b ->
                // Skip comparing objects to themselves.
                if (a === b) return

                if (!a.isMatching(b)) {
                    breaks[name].push([a.instanceName, b.instanceName])
                }
            }
        }
        return breaks
    }
}