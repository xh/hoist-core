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
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.Lock
import static io.xh.hoist.util.DateTimeUtils.SECONDS

class ClusterService extends BaseService {

    static HazelcastInstance instance = createInstance()

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

    List<Map> getMembers() {
        def masterName = getMasterName()
        cluster.members.collect { Member member ->
            def name = member.getAttribute('instanceName')
            return [
                name    : name,
                address : member.address.toString(),
                isMaster: masterName == name
            ]
        }
    }

    //------------------------
    // Distributed Resources
    //------------------------
    <K, V> ReplicatedMap<K, V> getReplicatedMap(String id) {
        instance.getReplicatedMap(id)
    }

    IMap getMap(String id) {
        instance.getMap(id)
    }

    ITopic getTopic(String id) {
        instance.getTopic(id)
    }

    //------------------------
    // Distributed execution
    //------------------------
    IExecutorService getExecutorService() {
        return instance.getExecutorService('default')
    }

    void executeOnAllMembers(Runnable r) {
        executorService.executeOnAllMembers(r)
    }

    <T> Map<Member, Future<T>> submitToAllMembers(Callable<T> c) {
        executorService.submitToAllMembers(c)
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
