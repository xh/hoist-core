package io.xh.hoist.cluster

import groovy.transform.MapConstructor
import io.xh.hoist.BaseService
import io.xh.hoist.json.JSONFormat

import static io.xh.hoist.util.Utils.getAppContext
import static io.xh.hoist.util.DateTimeUtils.SECONDS

class ClusterHealthCheckService extends BaseService {
    def distributedObjectAdminService,
        serviceManagerService

    Map runHealthCheck() {
        def checks = clusterService.submitToAllInstances(new ListClusterHealthChecks()),
            primaryMap = (checks[clusterService.primaryName].value as List<ClusterHealthCheck>).collectEntries {[it.key, it]} as Map<String, ClusterHealthCheck>,
            asOf = System.currentTimeMillis(),
            results = [:],
            checkCount = 0,
            mismatchCount = 0

        checks.each { String clusterName, ClusterResponse<List<ClusterHealthCheck>> clusterResponse ->
            if (clusterName == clusterService.primaryName) return
            clusterResponse.value.each { ClusterHealthCheck nonPrimaryRecord ->
                def primaryRecord = primaryMap[nonPrimaryRecord.key]
                if (!primaryRecord.test(nonPrimaryRecord, asOf)) {
                    if (results[nonPrimaryRecord.key]) {
                        results[nonPrimaryRecord.key][clusterName] = nonPrimaryRecord
                    } else {
                        results[nonPrimaryRecord.key] = [
                            [clusterService.primaryName]: primaryRecord,
                            [clusterName]              : nonPrimaryRecord
                        ]
                    }
                    mismatchCount++
                 }
                checkCount++
            }
        }

        return [
            clusterCount: checks.size(),
            checkedObjectsCount: primaryMap.size(),
            checkCount: checkCount,
            mismatchCount: mismatchCount,
            mismatches: results
        ]
    }

    List<ClusterHealthCheck> listClusterHealthChecks() {
       [
           *listClusterHealthChecksForDistributedObjects(),
           *listClusterHealthChecksForServices()
       ].findAll { it } as List<ClusterHealthCheck>
    }
    static class ListClusterHealthChecks extends ClusterRequest<List<ClusterHealthCheck>> {
        List<ClusterHealthCheck> doCall() {
            appContext.clusterHealthCheckService.listClusterHealthChecks()
        }
    }

    List<ClusterHealthCheck> listClusterHealthChecksForDistributedObjects() {
        distributedObjectAdminService.listObjects().collect {
            getClusterHealthCheckForAdminStats(it)
        }.findAll { it }
    }

    List<ClusterHealthCheck> listClusterHealthChecksForServices() {
        serviceManagerService.listStats().collectMany { Map svcStats ->
           [
               getClusterHealthCheckForAdminStats(svcStats),
               *(svcStats.resources as List<Map>)?.collect {
                   getClusterHealthCheckForAdminStats(it, svcStats.name as String)
               }.findAll { it }
           ] as List<ClusterHealthCheck>
        }
    }

    ClusterHealthCheck getClusterHealthCheckForAdminStats(Map adminStats, String parentName = null) {
        def name = parentName ? "$parentName[${adminStats.name}]" : adminStats.name as String,
            type = adminStats.type as String

        switch (type) {
            // Distributed objects
            case 'Replicated Map':
            case 'Map':
            case 'Set':
            case 'Hibernate Cache':
                return new ClusterHealthCheck(
                    name: name,
                    type: type,
                    checks: [size: adminStats.size],
                    timestamp: adminStats.lastUpdateTime
                )

            // Hoist objects
            case 'Cache (replicated)' :
                return new ClusterHealthCheck(
                    name: name,
                    type: type,
                    checks: [
                        count: adminStats.count,
                        latestTimestamp: adminStats.latestTimestamp
                    ],
                    timestamp: adminStats.latestTimestamp
                )
            case 'CachedValue (replicated)':
                return new ClusterHealthCheck(
                    name: name,
                    type: type,
                    checks: [timestamp: adminStats.timestamp],
                    timestamp: adminStats.timestamp
                )

            // Default
            default:
                // Opt-in to cluster health check
                if (adminStats.clusterHealthCheckConfig) {
                    def config = adminStats.clusterHealthCheckConfig as Map,
                        defaultName = config.name ?: name,
                        defaultType = config.type ?: type ?: name.endsWith('Service') ? 'Service' : null
                    return new ClusterHealthCheck(
                        name: defaultName,
                        type: defaultType,
                        checks: config.checks,
                        timestamp: config.timestamp
                    )
                } else {
                    return null
                }
        }
    }

    @MapConstructor
    class ClusterHealthCheck implements JSONFormat {
        static final long CONCURRENCY_INTERVAL = 2 * SECONDS

        // Composite key of name and type
        String name
        String type

        /** Will be compared across instances. */
        Map<String, Object> checks
        /** If the timestamp is too recent, comparison will be skipped. */
        Long timestamp

        Boolean test(ClusterHealthCheck other, Long asOf) {
            if (!sameObjectAs(other)) throw new RuntimeException('Cannot compare different objects.')
            return skipTest(asOf) || other.skipTest(asOf) || this.checks == other.checks
        }

        boolean skipTest(Long asOf) {
            return !timestamp || asOf - timestamp < CONCURRENCY_INTERVAL
        }

        String getKey() {
            return "$name-$type"
        }

        boolean sameObjectAs(ClusterHealthCheck other) {
            return key == other.key
        }

        Map formatForJSON() {
            return [
                name: name,
                type: type,
                checks: checks,
                timestamp: timestamp,
                skipTest: skipTest(System.currentTimeMillis())
            ]
        }
    }
}
