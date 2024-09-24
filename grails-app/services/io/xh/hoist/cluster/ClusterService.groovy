package io.xh.hoist.cluster

import com.hazelcast.cluster.Cluster
import com.hazelcast.cluster.Member
import com.hazelcast.cluster.MembershipEvent
import com.hazelcast.cluster.MembershipListener
import com.hazelcast.collection.ISet
import com.hazelcast.config.Config
import com.hazelcast.core.DistributedObject
import com.hazelcast.core.Hazelcast
import com.hazelcast.core.HazelcastInstance
import com.hazelcast.core.IExecutorService
import com.hazelcast.map.IMap
import com.hazelcast.replicatedmap.ReplicatedMap
import com.hazelcast.topic.ITopic
import io.xh.hoist.BaseService
import io.xh.hoist.ClusterConfig
import io.xh.hoist.exception.InstanceNotFoundException
import io.xh.hoist.util.Utils
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationListener

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
    static HazelcastInstance hzInstance

    private static ClusterConfig clusterConfig

    static {
        // Create cluster/instance identifiers statically so logging can access early in lifecycle
        if (Utils.appCode) {  // ... do not create during build
            clusterConfig = createConfig()
            clusterName = clusterConfig.clusterName
            instanceName = clusterConfig.instanceName
            System.setProperty('io.xh.hoist.hzInstanceName', instanceName)
        }
    }

    /** Are multi-instance clusters enabled? */
    static boolean getMultiInstanceEnabled() {
        clusterConfig.multiInstanceEnabled
    }

    /**
     * Called by Framework to initialize the Hazelcast instance.
     * @internal
     */
    static initializeInstance() {
        hzInstance = Hazelcast.newHazelcastInstance(clusterConfig.createConfig())
    }

    void init() {
        adjustPrimaryStatus()
        cluster.addMembershipListener([
            memberAdded  : { MembershipEvent e -> adjustPrimaryStatus(e.members) },
            memberRemoved: { MembershipEvent e -> adjustPrimaryStatus(e.members) }
        ] as MembershipListener)

        super.init()
    }

    private boolean _isPrimary = false


    /**
     * Is this instance ready for requests?
     */
    boolean isReady = false

    /**
     * The Hazelcast member representing the 'primary' instance.
     *
     * Apps typically use the primary instance to execute any run-once logic on the cluster.
     *
     * We use a simple definition documented by Hazelcast: The oldest member.
     * See https://docs.hazelcast.org/docs/4.0/javadoc/com/hazelcast/cluster/Cluster.html#getMembers
     */
    Member getPrimary() {
        cluster.members.iterator().next()
    }

    /** The instance name of the primary server.*/
    String getPrimaryName() {
        primary.getAttribute('instanceName')
    }

    /** The instance name of the local server.*/
    String getLocalName() {
        localMember.getAttribute('instanceName')
    }

    /** Is the local instance the primary instance? */
    boolean getIsPrimary() {
        // Cache until we ensure our implementation lightweight enough -- also supports logging.
        return _isPrimary
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

    /**
     * The distributed objects available in the cluster
     */
    Collection<DistributedObject> getDistributedObjects() {
        hzInstance.distributedObjects
    }

    /**
     * Is the given instance a member of the cluster?
     */
    boolean isMember(String instanceName) {
        cluster.members.any { it.getAttribute('instanceName') == instanceName }
    }

    //------------------------
    // Distributed execution
    //------------------------
    IExecutorService getExecutorService() {
        return hzInstance.getExecutorService('default')
    }

    <T> ClusterResponse<T> submitToInstance(ClusterRequest<T> c, String instance) {
            executorService.submitToMember(c, getMember(instance)).get()
    }

    <T> Map<String, ClusterResponse<T>> submitToAllInstances(ClusterRequest<T> c) {
        executorService
            .submitToAllMembers(c)
            .collectEntries { member, f -> [member.getAttribute('instanceName'), f.get()] }
    }

    //------------------
    // Create Objects
    //-----------------
    static <K, V> IMap<K, V> configuredIMap(String name, Closure customizer = null) {
        customizer?.call(hzConfig.getMapConfig(name))
        hzInstance.getMap(name)
    }

    static <V> ISet<V> configuredISet(String name, Closure customizer = null) {
        customizer?.call(hzConfig.getSetConfig(name))
        hzInstance.getSet(name)
    }

    static <K, V> ReplicatedMap<K, V> configuredReplicatedMap(String name, Closure customizer = null) {
        customizer?.call(hzConfig.getReplicatedMapConfig(name))
        hzInstance.getReplicatedMap(name)
    }

    static <M> ITopic<M> configuredTopic(String name, Closure customizer = null) {
        customizer?.call(hzConfig.getTopicConfig(name))
        hzInstance.getTopic(name)
    }

    static <M> ITopic<M> configuredReliableTopic(
        String name,
        Closure customizer = null,
        Closure ringBufferCustomizer = null
    ) {
        ringBufferCustomizer?.call(hzConfig.getRingbufferConfig(name))
        customizer?.call(hzConfig.getReliableTopicConfig(name))
        hzInstance.getReliableTopic(name)
    }

    //------------------------------------
    // Implementation
    //------------------------------------
    private Cluster getCluster() {
        hzInstance.cluster
    }

    private void adjustPrimaryStatus(Set<Member> members = cluster.members) {
        // Accept members explicitly to avoid race conditions when responding to MembershipEvents
        // (see https://docs.hazelcast.org/docs/4.0/javadoc/com/hazelcast/cluster/MembershipEvent.html)
        // Not sure if we ever abdicate, could network failures cause the primary to leave and rejoin cluster?
        def newIsPrimary = members.iterator().next().localMember()
        if (_isPrimary != newIsPrimary) {
            _isPrimary = newIsPrimary
            _isPrimary ?
                logInfo("I have become the primary instance. All hail me, '$instanceName'") :
                logInfo('I am no longer the primary instance.')
        }
    }

    private Member getMember(String instanceName) {
        def ret = cluster.members.find { it.getAttribute('instanceName') == instanceName }
        if (!ret) throw new InstanceNotFoundException("Unable to find cluster instance $instanceName")
        return ret
    }

    private static ClusterConfig createConfig() {
        def clazz
        try {
            clazz = Class.forName(Utils.appPackage + '.ClusterConfig')
        } catch (ClassNotFoundException e) {
            clazz = Class.forName('io.xh.hoist.ClusterConfig')
        }
        return (clazz.getConstructor().newInstance() as ClusterConfig)
    }

    private static Config getHzConfig() {
        hzInstance.config
    }

    void onApplicationEvent(ApplicationReadyEvent event) {
       isReady = true
    }

    Map getAdminStats() {[
        clusterId   : cluster.clusterState.id,
        instanceName: instanceName,
        primaryName  : primaryName,
        isPrimary    : isPrimary,
        members     : cluster.members.collect { it.getAttribute('instanceName') }
    ]}

}