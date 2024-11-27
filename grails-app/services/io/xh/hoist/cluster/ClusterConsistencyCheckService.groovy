package io.xh.hoist.cluster

import io.xh.hoist.BaseService
import io.xh.hoist.admin.DistributedObjectAdminService
import io.xh.hoist.admin.ServiceManagerService

import static io.xh.hoist.util.Utils.getAppContext

class ClusterConsistencyCheckService extends BaseService {
    DistributedObjectAdminService distributedObjectAdminService
    ServiceManagerService serviceManagerService

    Map runClusterConsistencyCheck() {
        def responsesByInstance = clusterService.submitToAllInstances(new ListClusterConsistencyChecks()),
            primaryList = responsesByInstance[clusterService.primaryName].value as List<ClusterConsistencyCheck>,
            primaryMap = primaryList.collectEntries {[it.key, it]} as Map<String, ClusterConsistencyCheck>,
            mismatches = [:],
            checkCount = 0,
            mismatchCount = 0

        responsesByInstance.each { clusterName, clusterResponse ->
            // No need to compare primary to itself
            if (clusterName == clusterService.primaryName) return
            clusterResponse.value.each { ClusterConsistencyCheck nonPrimaryRecord ->
                def primaryRecord = primaryMap[nonPrimaryRecord.key]
                // Compare each nonPrimary record to its primary counterpart
                if (!primaryRecord.test(nonPrimaryRecord)) {
                    if (mismatches[nonPrimaryRecord.key]) {
                        mismatches[nonPrimaryRecord.key][clusterName] = nonPrimaryRecord
                    } else {
                        mismatches[nonPrimaryRecord.key] = [
                            (clusterService.primaryName): primaryRecord,
                            (clusterName)               : nonPrimaryRecord
                        ]
                    }
                    mismatchCount++
                 }
                checkCount++
            }
        }

        return [
            instanceCount: responsesByInstance.size(),
            objectsCount: primaryMap.size(),
            checkCount: checkCount,
            mismatchCount: mismatchCount,
            mismatches: mismatches
        ]
    }

    List<ClusterConsistencyCheck> listClusterConsistencyChecks() {
       [
           *listClusterConsistencyChecksForDistributedObjects(),
           *listClusterConsistencyChecksForServices()
       ].findAll { it } as List<ClusterConsistencyCheck>
    }
    static class ListClusterConsistencyChecks extends ClusterRequest<List<ClusterConsistencyCheck>> {
        List<ClusterConsistencyCheck> doCall() {
            appContext.clusterConsistencyCheckService.listClusterConsistencyChecks()
        }
    }

    List<ClusterConsistencyCheck> listClusterConsistencyChecksForDistributedObjects() {
        distributedObjectAdminService.listObjects().collect {
            getClusterConsistencyCheckForAdminStats(it)
        }.findAll { it }
    }

    List<ClusterConsistencyCheck> listClusterConsistencyChecksForServices() {
        serviceManagerService.listStats().collectMany { Map svcStats ->
           [
               // The service itself, if it has custom cluster consistency checks
               getClusterConsistencyCheckForAdminStats(svcStats),
               // All of the service's resources
               *(svcStats.resources as List<Map>)?.collect {
                   getClusterConsistencyCheckForAdminStats(it, svcStats.name as String)
               }.findAll { it }
           ] as List<ClusterConsistencyCheck>
        }
    }

    // ------------------------------
    // Implementation
    // ------------------------------
    ClusterConsistencyCheck getClusterConsistencyCheckForAdminStats(Map adminStats, String parentName = null) {
        def name = parentName ? "$parentName[${adminStats.name}]" : adminStats.name as String,
            type = adminStats.type as String

        switch (type) {
            // Distributed objects
            case 'Replicated Map':
            case 'Map':
            case 'Set':
            case 'Hibernate Cache':
                return new ClusterConsistencyCheck(
                    name: name,
                    type: type,
                    checks: [size: adminStats.size],
                    timestamp: adminStats.lastUpdateTime as Long
                )

            // Hoist objects
            case 'Cache (replicated)' :
                return new ClusterConsistencyCheck(
                    name: name,
                    type: type,
                    checks: [
                        count: adminStats.count,
                        latestTimestamp: adminStats.latestTimestamp
                    ],
                    timestamp: adminStats.latestTimestamp as Long
                )
            case 'CachedValue (replicated)':
                return new ClusterConsistencyCheck(
                    name: name,
                    type: type,
                    checks: [timestamp: adminStats.timestamp],
                    timestamp: adminStats.timestamp as Long
                )

            // Default
            default:
                // Opt-in to cluster health check
                if (adminStats.clusterConsistencyCheckConfig) {
                    def config = adminStats.clusterConsistencyCheckConfig as Map,
                        defaultName = config.name ?: name,
                        defaultType = config.type ?: type ?: name.endsWith('Service') ? 'Service' : null
                    return new ClusterConsistencyCheck(
                        name: defaultName,
                        type: defaultType,
                        checks: config.checks as Map,
                        timestamp: config.timestamp as Long
                    )
                } else {
                    return null
                }
        }
    }
}
