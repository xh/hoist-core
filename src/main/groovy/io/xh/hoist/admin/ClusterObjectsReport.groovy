package io.xh.hoist.admin

import io.xh.hoist.json.JSONFormat

/**
 * A consolidated report sourced from serialized {@link ClusterObjectInfo} objects.
 * These are DTOs to report on a distributed object with state that is useful to inspect
 * via the Admin Console and/or reconcile across different instances in the cluster.
 *
 * @see ClusterObjectsService
 */
class ClusterObjectsReport implements JSONFormat {

    // List of all of the distributed object data from all of the instances in the cluster.
    List<Map> info
    Map<String, List<List<String>>> breaks
    Long startTimestamp
    Long endTimestamp

    ClusterObjectsReport(List<Map> info, Long startTimestamp, Long endTimestamp) {
        this.info = info
        this.breaks = createBreaks()
        this.startTimestamp = startTimestamp
        this.endTimestamp = endTimestamp
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
        info.groupBy { it.name }
            .each { name, infoObjs ->
                [infoObjs, infoObjs].eachCombination { a, b ->
                    if (a !== b &&
                        (
                            a.comparableAdminStats != b.comparableAdminStats ||
                            a.comparableAdminStats.any { a.adminStats[it] != b.adminStats[it] }
                        )
                    ) {
                        breaks[name].push([a.instanceName, b.instanceName])
                    }
                }
            }
        return breaks
    }
}
