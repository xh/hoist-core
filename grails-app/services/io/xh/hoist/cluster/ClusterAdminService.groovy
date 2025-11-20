package io.xh.hoist.cluster

import io.xh.hoist.BaseService
import io.xh.hoist.util.Utils

import static io.xh.hoist.cluster.InstanceState.RUNNING
import static io.xh.hoist.util.Utils.appContext
import static io.xh.hoist.util.ClusterUtils.runOnAllInstances

/**
 * Reports on all instances within the current Hazelcast cluster, including a general list of all
 * live instances with summary stats/metrics for each + more detailed information on distributed
 * objects as seen by all instances, or any particular instance.
 */
class ClusterAdminService extends BaseService {

    Map getAdminStats() {
        return [
            name          : clusterService.instanceName,
            address       : clusterService.localMember.address.toString(),
            isPrimary     : clusterService.isPrimary,
            memory        : appContext.memoryMonitoringService.latestSnapshot,
            connectionPool: appContext.connectionPoolMonitoringService.latestSnapshot,
            wsConnections : appContext.webSocketService.allChannels.size(),
            startupTime   : ClusterService.startupTime,
            instanceState : ClusterService.instanceState,
            isReady       : ClusterService.instanceState == RUNNING
        ]
    }

    Collection<Map> getAllStats() {
        runOnAllInstances(this.&getAdminStats)
            .collect { name, result ->
                [
                    name   : name,
                    isReady: false,
                    *:(result.value ?: [:])
                ]
            }
    }

}