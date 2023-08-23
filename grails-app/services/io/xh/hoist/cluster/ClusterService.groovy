package io.xh.hoist.cluster

import com.hazelcast.cluster.Cluster
import com.hazelcast.cluster.Member
import com.hazelcast.core.Hazelcast
import com.hazelcast.config.Config
import com.hazelcast.core.HazelcastInstance
import com.hazelcast.core.IExecutorService
import com.hazelcast.map.IMap
import com.hazelcast.replicatedmap.ReplicatedMap
import com.hazelcast.topic.ITopic
import io.xh.hoist.BaseService
import io.xh.hoist.util.Utils

import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.Lock
import static io.xh.hoist.util.DateTimeUtils.SECONDS

class ClusterService extends BaseService {

    static HazelcastInstance instance = createInstance()

    Set mapIds = new ConcurrentHashMap().newKeySet()
    Set replicatedMapIds = new ConcurrentHashMap().newKeySet()
    Set topicIds = new ConcurrentHashMap().newKeySet()

    void init() {
        createTimer(
            name: 'electMaster',
            runFn: this.&electMaster,
            interval: 30 * SECONDS,
            delay: 15 * SECONDS
        )
        super.init()
    }

    //----------------------
    // Basic Introspection
    //----------------------
    String getInstanceName() {
        instance.config.instanceName
    }

    Cluster getCluster() {
        instance.cluster
    }

    String getMasterName() {
        instance.getCPSubsystem().getAtomicReference('masterName').get()
    }

    boolean getIsMaster() {
        masterName == instanceName
    }

    //------------------------
    // Distributed Resources
    //------------------------
    <K, V> ReplicatedMap<K, V> getReplicatedMap(String id) {
        def ret = instance.getReplicatedMap(id)
        replicatedMapIds.add(id);
        return ret
    }

    IMap getMap(String id) {
        def ret = instance.getMap(id)
        mapIds.add(id);
        return ret
    }

    ITopic getTopic(String id) {
        def ret = instance.getTopic(id)
        topicIds.add(id);
        return ret
    }

    //------------------------
    // Distributed execution
    //------------------------
    IExecutorService getExecutorService() {
        return instance.getExecutorService('default')
    }

    <T> Future<T> submitToMember(Callable<T> c, String name) {
        executorService.submitToMember(c, getMember(name))
    }

    <T> Map<String, Future<T>> submitToAllMembers(Callable<T> c) {
        executorService
            .submitToAllMembers(c)
            .collectEntries { Member member, Future<T> result ->
                [member.getAttribute('instanceName'), result]
            }
    }


    //------------------------------------
    // Implementation
    //------------------------------------
    private void electMaster() {
        Lock l = instance.CPSubsystem.getLock('masterElection')
        if (l.tryLock(1, TimeUnit.SECONDS)) {
            try {
                def members = cluster.members,
                    masterName = getMasterName(),
                    master = masterName ? members.find {it.getAttribute('instanceName') == masterName} : null,
                    first = members ? members.iterator().next() : null

                // Master not found, or needs to replaced.  Do so!
                if (!master) {
                    def newMasterName = first.getAttribute('instanceName')
                    setMasterName(newMasterName)
                    logInfo("A new Master has been elected. All hail [$newMasterName].")
                } else {
                    logDebug("Master has been reconfirmed [$masterName].")
                }

            } finally {
                l.unlock()
            }
        }
    }

    private Member getMember(String name) {
        def ret = cluster.members.find { it.getAttribute('instanceName') == name }
        if (!ret) throw new RuntimeException("Unable to find cluster member $name")
        return ret
    }

    private void setMasterName(String s) {
        instance.CPSubsystem.getAtomicReference('masterName').set(s)
    }

    private static HazelcastInstance createInstance() {
        def config = createClusterConfig()
        return Hazelcast.newHazelcastInstance(config)
    }

    private static Config createClusterConfig() {
        def clusterName = Utils.appName + '_' + Utils.appEnvironment + '_' + Utils.appVersion,
            instanceName = UUID.randomUUID().toString().take(8)

        // The built-in xml.file in the app reads these.  It is used by hibernate 2nd-level cache
        // config and for any app specific settings. Set to ensure we have only one cluster/instance
        System.setProperty('io.xh.hoist.hzClusterName', clusterName)
        System.setProperty('io.xh.hoist.hzInstanceName', instanceName)

        def config = new Config()
        config.instanceName = instanceName
        config.clusterName = clusterName

        // Additional configs
        config.memberAttributeConfig.setAttribute('instanceName', instanceName)
        config.networkConfig.join.multicastConfig.enabled = true

        return config
    }
}
