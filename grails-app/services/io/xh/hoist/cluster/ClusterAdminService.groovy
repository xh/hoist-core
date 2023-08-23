package io.xh.hoist.cluster

import com.hazelcast.cache.impl.CacheProxy
import com.hazelcast.map.IMap
import com.hazelcast.replicatedmap.ReplicatedMap
import com.hazelcast.topic.ITopic
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

    Collection<Map> getObjectStats() {
        def svc = clusterService,
            ret = []

        svc.replicatedMapIds.each {
            ReplicatedMap map = svc.getReplicatedMap(it)
            ret << [id: 'repMap_' + it, name: map.getName(), size: map.size(), objectType: 'Replicated Map', stats: null]
        }

        svc.mapIds.each {
            IMap map = svc.getMap(it)
            ret << [id: 'map_' + it, name: map.getName(), size: map.size(), objectType: 'Map', stats: null]
        }

        svc.topicIds.each {
            ITopic t = svc.getTopic(it)
            ret << [id: 'topic_' + it, name: t.getName(), size: null, objectType: 'Topic', stats: null]
        }

        clusterService.instance.distributedObjects.findAll().each {
            if (it instanceof CacheProxy) {
                def name = it.getName()
                ret << [id: 'hibernate_' + name, name: name, size: it.size(), objectType: 'Hibernate Cache', stats: null]
            }
        }

        return ret
    }
}
