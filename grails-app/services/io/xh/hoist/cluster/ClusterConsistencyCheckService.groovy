package io.xh.hoist.cluster

import io.xh.hoist.BaseService
import io.xh.hoist.admin.DistributedObjectAdminService
import io.xh.hoist.admin.ServiceManagerService

import static io.xh.hoist.util.Utils.getAppContext

class ClusterConsistencyCheckService extends BaseService {
    DistributedObjectAdminService distributedObjectAdminService
    ServiceManagerService serviceManagerService

    List<ClusterConsistencyResult> runClusterConsistencyCheck() {
        def responsesByInstance = clusterService.submitToAllInstances(new ListChecks())

        // Group the ClusterConsistencyCheck objects into ClusterConsistencyResult objects
        Map<String,ClusterConsistencyResult> resultMap = [:]
        responsesByInstance.each { instanceName, instanceValue ->
            instanceValue.value.each { check ->
                if (!resultMap[check.key]) {
                    resultMap[check.key] = new ClusterConsistencyResult(check, instanceName)
                } else {
                    resultMap[check.key].add(check, instanceName)
                }
            }
        }

        return resultMap.values().toList()
    }

    List<ClusterConsistencyCheck> listChecks() {
       [
           *listChecksForDistributedObjects(),
           *listChecksForServices()
       ].findAll { it } as List<ClusterConsistencyCheck>
    }
    static class ListChecks extends ClusterRequest<List<ClusterConsistencyCheck>> {
        List<ClusterConsistencyCheck> doCall() {
            appContext.clusterConsistencyCheckService.listChecks()
        }
    }

    List<ClusterConsistencyCheck> listChecksForDistributedObjects() {
        distributedObjectAdminService.listObjects().collect {
            getCheckFromAdminStats(it)
        }.findAll { it }
    }

    List<ClusterConsistencyCheck> listChecksForServices() {
        serviceManagerService.listStats().collectMany { Map svcStats ->
           [
               // The service itself, if it has custom cluster consistency checks
               getCheckFromAdminStats(svcStats),
               // All of the service's resources
               *(svcStats.resources as List<Map>)?.collect {
                   getCheckFromAdminStats(it, svcStats.name as String)
               }.findAll { it }
           ] as List<ClusterConsistencyCheck>
        }
    }

    // ------------------------------
    // Implementation
    // ------------------------------
    ClusterConsistencyCheck getCheckFromAdminStats(Map adminStats, String parentName = null) {
        def name = parentName ? "$parentName[${adminStats.name}]" : adminStats.name as String,
            type = adminStats.type as String

        switch (type) {
            // Distributed objects
            case 'Replicated Map':
            case 'Map':
            case 'Set':
                return new ClusterConsistencyCheck(
                    name: name,
                    type: type,
                    owner: parentName,
                    checks: [size: adminStats.size],
                    lastUpdated: adminStats.lastUpdateTime as Long
                )

            case 'Hibernate Cache':
                return new ClusterConsistencyCheck(
                    name: name,
                    type: type,
                    owner: 'Hibernate',
                    checks: [size: adminStats.size],
                    lastUpdated: adminStats.lastUpdateTime as Long
                )

            // Hoist objects
            case 'Cache (replicated)' :
                return new ClusterConsistencyCheck(
                    name: name,
                    type: type,
                    owner: parentName,
                    checks: [
                        count: adminStats.count,
                        latestTimestamp: adminStats.latestTimestamp
                    ],
                    lastUpdated: adminStats.latestTimestamp as Long
                )
            case 'CachedValue (replicated)':
                return new ClusterConsistencyCheck(
                    name: name,
                    type: type,
                    owner: parentName,
                    checks: [
                        size: adminStats.size,
                        timestamp: adminStats.timestamp
                    ],
                    lastUpdated: adminStats.timestamp as Long
                )

            // Default
            default:
                // Opt-in to cluster health check
                if (adminStats.clusterConsistencyCheckConfig) {
                    def config = adminStats.clusterConsistencyCheckConfig as Map,
                        defaultName = config.name ?: name,
                        defaultType = config.type ?: type ?: name.endsWith('Service') ? 'Service' : null,
                        defaultOwner = config.owner ?: parentName
                    return new ClusterConsistencyCheck(
                        name: defaultName,
                        type: defaultType,
                        owner: defaultOwner,
                        checks: config.checks as Map,
                        lastUpdated: config.lastUpdated as Long
                    )
                } else {
                    return null
                }
        }
    }
}
