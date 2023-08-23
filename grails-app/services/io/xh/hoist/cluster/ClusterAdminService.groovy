package io.xh.hoist.cluster

import com.hazelcast.map.IMap
import com.hazelcast.map.LocalMapStats
import com.hazelcast.replicatedmap.LocalReplicatedMapStats
import com.hazelcast.replicatedmap.ReplicatedMap
import com.hazelcast.topic.ITopic
import com.hazelcast.topic.LocalTopicStats
import io.xh.hoist.BaseService
import io.xh.hoist.util.Utils

import java.util.concurrent.Callable

import static io.xh.hoist.util.Utils.appContext

class ClusterAdminService extends BaseService {

    Map getLocalStats() {
        return new HashMap(
            name             :  clusterService.instanceName,
            address          :  clusterService.cluster.localMember.address.toString(),
            isMaster         :  isMaster,
            memory           :  appContext.memoryMonitoringService.latestSnapshot,
            connectionPool   :  appContext.connectionPoolMonitoringService.latestSnapshot,
            wsConnections    :  appContext.webSocketService.allChannels.size(),
            startupTime      :  Utils.startupTime
        )
    }

    Collection<Map> getAllStats() {
        clusterService.submitToAllMembers(new GetLocalStatsTask())
            .collect {name, value -> [
                *:value.get(),
                isLocal: name == clusterService.instanceName
            ]}
    }
    static class GetLocalStatsTask implements Callable, Serializable {
        def call() {
            return appContext.clusterAdminService.localStats
        }
    }

    Collection<Map> listObjects() {
        clusterService.listObjects().collect {obj ->
            def ret = [
                name      : obj.getName(),
                objectType: 'Unknown',
                stats     : [:],
                size      : null
            ]

            switch (obj) {
                case ReplicatedMap:
                    LocalReplicatedMapStats stats = obj.getReplicatedMapStats()
                    ret << [
                        objectType: 'Replicated Map',
                        size      : obj.size(),
                        stats     : [
                            ownedEntryCount: stats.ownedEntryCount,
                            heapCost      : stats.heapCost,
                            hits          : stats.hits,
                            lastUpdateTime: stats.lastUpdateTime,
                            lastAccessTime: stats.lastAccessTime
                        ]
                    ]
                    break
                case IMap:
                    LocalMapStats stats = obj.getLocalMapStats()
                    ret << [
                        objectType: 'Distributed Map',
                        size      : obj.size(),
                        stats     : [
                            ownedEntryCount: stats.ownedEntryCount,
                            heapCost       : stats.heapCost,
                            hits           : stats.hits,
                            lastUpdateTime : stats.lastUpdateTime,
                            lastAccessTime  : stats.lastAccessTime
                        ]
                    ]
                    break
                case ITopic:
                    LocalTopicStats stats = obj.getLocalTopicStats()
                    ret << [
                        objectType: 'Topic',
                        stats     : [
                            publishOperationCount: stats.publishOperationCount,
                            receiveOperationCount: stats.receiveOperationCount,
                            creationTime         : stats.creationTime
                        ]
                    ]
                    break
            }
            return ret
        }
    }

    void clearObjects(List<String> names) {
        def all = clusterService.listObjects()
        names.each { name ->
            def obj = all.find { it.getName() == name }
            if (obj instanceof ReplicatedMap || obj instanceof IMap) {
                obj.clear()
                logInfo('Cleared Object ' + name)
            } else {
                logWarn('Cannot clear object' + name)
            }
        }
    }
}
