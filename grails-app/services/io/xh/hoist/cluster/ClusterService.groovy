package io.xh.hoist.cluster

import com.hazelcast.cluster.Cluster
import com.hazelcast.cluster.Member
import com.hazelcast.cluster.MembershipEvent
import com.hazelcast.cluster.MembershipListener
import com.hazelcast.collection.ISet
import com.hazelcast.config.CacheSimpleConfig
import com.hazelcast.config.EvictionPolicy
import com.hazelcast.config.MaxSizePolicy
import com.hazelcast.core.DistributedObject
import com.hazelcast.core.Hazelcast
import com.hazelcast.config.Config
import com.hazelcast.core.HazelcastInstance
import com.hazelcast.core.IExecutorService
import com.hazelcast.map.IMap
import com.hazelcast.replicatedmap.ReplicatedMap
import com.hazelcast.topic.ITopic
import io.xh.hoist.BaseService
import io.xh.hoist.util.Utils
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationListener

import javax.management.InstanceNotFoundException
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Future

class ClusterService extends BaseService implements ApplicationListener<ApplicationReadyEvent> {


    /**
     * Name of Hazelcast cluster.
     *
     * This value identifies the cluster to attach to, create and is unique to this
     * application, version, and environment.
     */
    static final String clusterName

    /**
     * Name of this instance.
     *
     * A randomly chosen unique identifier.
     */
    static final String instanceName

    /**
     * The underlying embedded Hazelcast instance.
     * Use for accessing the native Hazelcast APIs.
     */
    static final HazelcastInstance hzInstance


    static {
        // Create this statically so hibernate config can access it.
        clusterName = Utils.appCode + '_' + Utils.appEnvironment + '_' + Utils.appVersion
        instanceName = UUID.randomUUID().toString().take(8)
        System.setProperty('io.xh.hoist.hzInstanceName', instanceName)
        hzInstance = createInstance()
    }

    void init() {
        adjustMasterStatus()
        cluster.addMembershipListener([
            memberAdded  : { MembershipEvent e -> adjustMasterStatus(e.members) },
            memberRemoved: { MembershipEvent e -> adjustMasterStatus(e.members) }
        ] as MembershipListener)

        super.init()
    }

    //--------------------------------------------------------------
    // Lists of distributed data structured accessed on this server.
    //---------------------------------------------------------------
    Set mapIds = new ConcurrentHashMap().newKeySet()
    Set setIds = new ConcurrentHashMap().newKeySet()
    Set replicatedMapIds = new ConcurrentHashMap().newKeySet()
    Set topicIds = new ConcurrentHashMap().newKeySet()

    private boolean _isMaster = false



    /**
     * Is this instance ready for requests?
     */
    boolean isReady = false

    /**
     * The Hazelcast member representing the 'master' instance.
     *
     * Apps typically use the master instance to execute any run-once logic on the cluster.
     *
     * We use a simple master definition documented by Hazelcast: The oldest member.
     * See https://docs.hazelcast.org/docs/4.0/javadoc/com/hazelcast/cluster/Cluster.html#getMembers
     */
    Member getMaster() {
        cluster.members.iterator().next()
    }

    /** The instance name of the master server.*/
    String getMasterName() {
        master.getAttribute('instanceName')
    }

    /** Is the local instance the master instance? */
    boolean getIsMaster() {
        // Cache until we ensure our implementation lightweight enough -- also supports logging.
        return _isMaster
    }

    /** The Hazelcast member representing this instance. */
    Member getLocalMember() {
        return cluster.localMember
    }

    /**
     * Shutdown this instance.
     */
    void shutdownInstance() {
        System.exit(0)
    }


    //------------------------
    // Distributed Resources
    //------------------------
    <K, V> ReplicatedMap<K, V> getReplicatedMap(String id) {
        def ret = hzInstance.getReplicatedMap(id)
        replicatedMapIds.add(id);
        ret
    }

    <K, V> IMap<K, V> getMap(String id) {
        def ret = hzInstance.getMap(id)
        mapIds.add(id);
        ret
    }

    <V> ISet<V> getSet(String id) {
        def ret = hzInstance.getSet(id)
        setIds.add(id);
        ret
    }


    <M> ITopic<M> getTopic(String id) {
        def ret = hzInstance.getTopic(id)
        topicIds.add(id);
        ret
    }

    Collection<DistributedObject> listObjects() {
        def svc = clusterService,
            ret = []

        svc.replicatedMapIds.each { ret << svc.getReplicatedMap(it) }
        svc.mapIds.each { ret << svc.getMap(it) }
        svc.setIds.each { ret << svc.getSet(it) }
        svc.topicIds.each { ret << svc.getTopic(it) }
        ret
    }

    //------------------------
    // Distributed execution
    //------------------------
    IExecutorService getExecutorService() {
        return hzInstance.getExecutorService('default')
    }

    <T> T submitToInstance(Callable<T> c, String instanceName) {
        executorService.submitToMember(c, getMember(instanceName)).get()
    }

    <T> Map<String, Map> submitToAllInstances(Callable<T> c) {
        executorService
            .submitToAllMembers(c)
            .collectEntries { Member member, Future<T> f ->
                def value, failure
                try {
                    value = f.get()
                } catch (Exception e) {
                    failure = e
                }
                [member.getAttribute('instanceName'), [value: value, failure: failure]]
            }
    }


    //------------------------------------
    // Implementation
    //------------------------------------
    private Cluster getCluster() {
        hzInstance.cluster
    }

    private void adjustMasterStatus(Set<Member> members = cluster.members) {
        // Accept members explicitly to avoid race conditions when responding to MembershipEvents
        // (see https://docs.hazelcast.org/docs/4.0/javadoc/com/hazelcast/cluster/MembershipEvent.html)
        // Not sure if we ever abdicate, could network failures cause master to leave and rejoin cluster?
        def newIsMaster = members.iterator().next().localMember()
        if (_isMaster != newIsMaster) {
            _isMaster = newIsMaster
            _isMaster ?
                logInfo("I have assumed the role of Master. All hail me, '$instanceName'") :
                logInfo('I have abdicated the role of Master.')
        }
    }

    private Member getMember(String instanceName) {
        def ret = cluster.members.find { it.getAttribute('instanceName') == instanceName }
        if (!ret) throw new InstanceNotFoundException("Unable to find cluster instance $instanceName")
        return ret
    }

    private static HazelcastInstance createInstance() {
        def config = new Config()

        // Specify core identity of the instance.
        config.instanceName = instanceName
        config.clusterName = clusterName
        config.memberAttributeConfig.setAttribute('instanceName', instanceName)

        // Start with default cache and network configurations
        getCacheConfigs().each {config.addCacheConfig(it)}
        config.networkConfig.join.multicastConfig.enabled = true

        // ... and optionally apply application configurations
        def clazz = Class.forName('ClusterConfig')
        clazz?.getMethod('configure', Config)?.invoke(null, config)

        return Hazelcast.newHazelcastInstance(config)
    }

    private static List<CacheSimpleConfig> getCacheConfigs() {
        [
            hibernateCache('io.xh.hoist.clienterror.ClientError'),
            hibernateCache('io.xh.hoist.config.AppConfig'),
            hibernateCache('io.xh.hoist.feedback.Feedback'),
            hibernateCache('io.xh.hoist.jsonblob.JsonBlob'),
            hibernateCache('io.xh.hoist.log.LogLevel'),
            hibernateCache('io.xh.hoist.monitor.Monitor'),
            hibernateCache('io.xh.hoist.pref.Preference'),
            hibernateCache('io.xh.hoist.pref.UserPreference'),
            hibernateCache('io.xh.hoist.track.TrackLog') {
                it.evictionConfig.size = 10000
            },

            hibernateCache('default-update-timestamps-region') { CacheSimpleConfig c ->
                c.evictionConfig.size = 1000
            }
        ]
    }

    private static CacheSimpleConfig hibernateCache(String name, Closure closure = null) {
        def ret = new CacheSimpleConfig(name)
        ret.statisticsEnabled = true
        ret.evictionConfig.maxSizePolicy = MaxSizePolicy.ENTRY_COUNT
        ret.evictionConfig.evictionPolicy = EvictionPolicy.LRU
        ret.evictionConfig.size = 5000
        closure?.call(ret)
        return ret
    }

    void onApplicationEvent(ApplicationReadyEvent event) {
       isReady = true
    }

    Map getAdminStats() {[
        clusterId   : cluster.clusterState.id,
        instanceName: instanceName,
        masterName  : masterName,
        isMaster    : isMaster,
        members     : cluster.members.collect { it.getAttribute('instanceName') }
    ]}

}