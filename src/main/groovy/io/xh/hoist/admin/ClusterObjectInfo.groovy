package io.xh.hoist.admin

import io.xh.hoist.AdminStats
import io.xh.hoist.json.JSONFormat

import static io.xh.hoist.util.Utils.getClusterService
import static java.util.Collections.emptyList
import static java.util.Collections.emptyMap

class ClusterObjectInfo implements JSONFormat {
    String name   // Absolute name. Make sure to use `svc.hzName(name)` on relative-named objects
    String type
    Map adminStats
    List<String> comparableAdminStats
    String instanceName
    String error

    ClusterObjectInfo(AdminStats target, Map meta = [:] ) {
        try {
            adminStats = target.adminStats
            comparableAdminStats = target.comparableAdminStats
        } catch (Exception e) {
            adminStats = emptyMap()
            comparableAdminStats = emptyList()
            error = "Error computing admin stats | ${e.message}"
        }
        name = meta.name ?: adminStats.name
        type = meta.type ?: adminStats.type
        instanceName = clusterService.localName
    }

    boolean isMatching(ClusterObjectInfo other) {
        comparableAdminStats == other.comparableAdminStats &&
            comparableAdminStats.every { f -> adminStats[f] == other.adminStats[f]}
    }

    Map formatForJSON() {
        return [
            name: name,
            type: type,
            instanceName: instanceName,
            adminStats: adminStats,
            comparableAdminStats: comparableAdminStats
        ]
    }
}