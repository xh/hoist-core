package io.xh.hoist.cluster

import com.hazelcast.cluster.Cluster
import com.hazelcast.cluster.Member
import com.hazelcast.core.Hazelcast
import com.hazelcast.config.Config
import com.hazelcast.core.HazelcastInstance
import com.hazelcast.map.IMap
import com.hazelcast.replicatedmap.ReplicatedMap
import com.hazelcast.topic.ITopic
import io.xh.hoist.BaseService
import io.xh.hoist.util.Utils

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.Lock
import static io.xh.hoist.util.DateTimeUtils.SECONDS

class ClusterService extends BaseService {

    private HazelcastInstance instance

    void init() {
        withInfo('Joined cluster') {
            def config = createClusterConfig()
            instance = Hazelcast.newHazelcastInstance(config)
            def clusterSize = instance.cluster.members.size()

            logInfo(
                "Joined cluster [${config.clusterName}] as [${config.instanceName}].  " +
                "${clusterSize -1} other member(s) present."
            )

            createTimer(
                name: 'electMaster',
                runFn: this.&electMaster,
                interval: 30 * SECONDS,
                delay: 15 * SECONDS
            )
        }
        super.init()
    }

    //----------------------
    // Basic Introspection
    //----------------------
    String getMasterName() {
        instance.getCPSubsystem().getAtomicReference('masterName').get()
    }

    String getInstanceName() {
        instance.config.instanceName
    }

    boolean getIsMaster() {
        masterName == instanceName
    }

    List<Map> getInstances() {
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
    ReplicatedMap getReplicatedMap(String id) {
        instance.getReplicatedMap(id)
    }

    IMap getMap(String id) {
        instance.getMap(id)
    }

    ITopic getTopic(String id) {
        instance.getTopic(id)
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

    private Cluster getCluster() {
        instance.cluster
    }

    private static Config createClusterConfig() {
        def instanceName = 'instance' + UUID.randomUUID().toString().take(8),
            clusterName = Utils.appName + '_' + Utils.appEnvironment + '_' + Utils.appVersion,
            config = new Config()

        // Core configs
        config.instanceName = instanceName
        config.clusterName = clusterName
        config.memberAttributeConfig.setAttribute('instanceName', instanceName)

        // network config
        def networkConfig = config.networkConfig,
            join = networkConfig.join
        join.multicastConfig.enabled = true

        return config
    }
}
