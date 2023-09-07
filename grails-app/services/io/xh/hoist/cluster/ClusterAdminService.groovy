package io.xh.hoist.cluster

import com.hazelcast.collection.ISet
import com.hazelcast.collection.LocalSetStats
import com.hazelcast.map.IMap
import com.hazelcast.map.LocalMapStats
import com.hazelcast.nearcache.NearCacheStats
import com.hazelcast.replicatedmap.LocalReplicatedMapStats
import com.hazelcast.replicatedmap.ReplicatedMap
import com.hazelcast.topic.ITopic
import com.hazelcast.topic.LocalTopicStats
import io.xh.hoist.BaseService
import io.xh.hoist.util.Utils

import java.util.concurrent.Callable

import static io.xh.hoist.util.Utils.appContext

class ClusterAdminService extends BaseService {

    Map getAdminStats() {
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
        clusterService.submitToAllInstances(new GetLocalStatsTask())
            .collect {name, value -> [
                *:value.get(),
                isLocal: name == clusterService.instanceName
            ]}
    }
    static class GetLocalStatsTask implements Callable, Serializable {
        def call() {
            return appContext.clusterAdminService.adminStats
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
                            heapCost       : stats.heapCost,
                            gets           : stats.getOperationCount,
                            puts           : stats.putOperationCount,
                            lastUpdateTime : stats.lastUpdateTime,
                            lastAccessTime : stats.lastAccessTime
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
                            gets           : stats.getOperationCount,
                            sets           : stats.setOperationCount,
                            puts           : stats.putOperationCount,
                            nearCache     : getNearCacheStats(stats.nearCacheStats),
                            lastUpdateTime : stats.lastUpdateTime,
                            lastAccessTime: stats.lastAccessTime
                        ]
                    ]
                    break
                case ISet:
                    LocalSetStats stats = obj.getLocalSetStats()
                    ret << [
                        objectType: 'Set',
                        size: obj.size(),
                        stats     : [
                            lastUpdateTime       : stats.lastUpdateTime,
                            lastAccessTime       : stats.lastAccessTime
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

    private Map getNearCacheStats(NearCacheStats stats) {
        if (!stats) return null
        [
            ownedEntryCount    : stats.ownedEntryCount,
            lastPersistenceTime: stats.lastPersistenceTime,
            hits               : stats.hits,
            misses             : stats.misses,
            ratio              : stats.ratio.round(2)
        ]
    }

    void clearObjects(List<String> names) {
        def all = clusterService.listObjects()
        names.each { name ->
            def obj = all.find { it.getName() == name }
            if (obj instanceof ReplicatedMap || obj instanceof IMap) {
                obj.clear()
                logInfo('Cleared Object', name)
            } else {
                logWarn('Cannot clear object', name)
            }
        }
    }
}
