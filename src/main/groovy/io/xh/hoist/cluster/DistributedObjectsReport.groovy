package io.xh.hoist.cluster

import groovy.transform.MapConstructor
import io.xh.hoist.json.JSONFormat

class DistributedObjectsReport implements JSONFormat {

    // List of all of the distributed object data from all of the instances in the cluster.
    List<DistributedObjectInfo> info
    // Roughly when this report was generated.
    Long timestamp
    // Map of mismatches
    Map<String, List<List<String>>> breaks

    DistributedObjectsReport(Map args) {
        info = args.info as List<DistributedObjectInfo>
        timestamp = args.timestamp as Long

        breaks = createBreaks()
    }

    Map formatForJSON() {
        return [
            info              : info,
            breaks            : breaks,
            timestamp         : timestamp
        ]
    }

    private Map<String, List<List<String>>> createBreaks() {
        Map<String, List<List<String>>> breaks = [:]
        info.groupBy { it.name }.each { name, infoObjs ->
            [infoObjs, infoObjs].eachCombination { a, b ->
                // Skip comparing objects to themselves.
                if (a === b) return

                if (!a.isMatching(b)) {
                    if (!breaks[name]) breaks[name] = []
                    breaks[name].push([a.instanceName, b.instanceName])
                }
            }
        }
        return breaks
    }
}