package io.xh.hoist.cluster

import io.xh.hoist.BaseService
import io.xh.hoist.util.Utils

import static io.xh.hoist.util.Utils.appContext
import static io.xh.hoist.util.Utils.getExceptionHandler

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
            isReady       : clusterService.isReady,
            memory        : appContext.memoryMonitoringService.latestSnapshot,
            connectionPool: appContext.connectionPoolMonitoringService.latestSnapshot,
            wsConnections : appContext.webSocketService.allChannels.size(),
            startupTime   : Utils.startupTime
        ]
    }

    Collection<Map> getAllStats() {
        runOnAllInstances('getAdminStats')
            .collect { name, result ->
                def ret = [
                    name   : name,
                    isReady: false
                ]
                if (result.value) {
                    ret << result.value
                } else {
                    exceptionHandler.handleException(
                        exception: result.exception,
                        logTo: this,
                        logMessage: "Exception getting stats for $name"
                    )
                }
                return ret
            }
    }

}