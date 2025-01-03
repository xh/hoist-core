package io.xh.hoist.admin


import io.xh.hoist.json.JSONFormat

class ClusterObjectsReport implements JSONFormat {

    // List of all of the distributed object data from all of the instances in the cluster.
    List<ClusterObjectInfo> info
    Map<String, List<List<String>>> breaks
    Long startTimestamp
    Long endTimestamp

    ClusterObjectsReport(Map args) {
        info = args.info as List<ClusterObjectInfo>
        breaks = createBreaks()
        startTimestamp = args.startTimestamp as Long
        endTimestamp = args.endTimestamp as Long
    }

    Map formatForJSON() {
        return [
            info              : info,
            breaks            : breaks,
            startTimestamp    : startTimestamp,
            endTimestamp      : endTimestamp
        ]
    }

    //------------------
    // Implementation
    //------------------
    private Map<String, List<List<String>>> createBreaks() {
        Map<String, List<List<String>>> breaks = [:].withDefault { [] }
        info.groupBy { it.name }.each { name, infoObjs ->
            [infoObjs, infoObjs].eachCombination { a, b ->
                if (a !== b && !a.isMatching(b)) {
                    breaks[name].push([a.instanceName, b.instanceName])
                }
            }
        }
        return breaks
    }
}