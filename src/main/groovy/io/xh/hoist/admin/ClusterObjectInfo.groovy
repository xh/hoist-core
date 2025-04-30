package io.xh.hoist.admin

import io.xh.hoist.AdminStats
import io.xh.hoist.json.JSONFormat
import io.xh.hoist.log.LogSupport

import static io.xh.hoist.util.Utils.getClusterService

class ClusterObjectInfo implements JSONFormat, LogSupport {
    String name   // Absolute name. Make sure to use `svc.hzName(name)` on relative-named objects
    String type
    Map adminStats
    List<String> comparableAdminStats
    String instanceName
    String error

    ClusterObjectInfo(Map config = [:] ) {
        try {
            def target = config.target as AdminStats
            adminStats = target.adminStats
            comparableAdminStats = target.comparableAdminStats
        } catch (Exception e) {
            adminStats = [:]
            comparableAdminStats = []
            error = "Error computing admin stats | ${e.message}"
            logError("Error computing admin stats", [_name: name], e)
        }
        name = config.name ?: adminStats.name
        type = config.type ?: adminStats.type
        instanceName = clusterService.localName
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