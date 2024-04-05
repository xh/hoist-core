package io.xh.hoist

import com.hazelcast.config.CacheSimpleConfig
import com.hazelcast.config.Config
import com.hazelcast.config.EvictionConfig
import com.hazelcast.config.EvictionPolicy
import com.hazelcast.config.InMemoryFormat
import com.hazelcast.config.MapConfig
import com.hazelcast.config.ReplicatedMapConfig
import com.hazelcast.config.SetConfig
import com.hazelcast.config.TopicConfig
import com.hazelcast.map.IMap
import com.hazelcast.replicatedmap.ReplicatedMap
import com.hazelcast.topic.ITopic
import com.hazelcast.collection.ISet
import com.hazelcast.config.MaxSizePolicy
import com.hazelcast.config.NearCacheConfig
import grails.core.GrailsClass
import info.jerrinot.subzero.SubZero
import io.xh.hoist.cache.Entry
import io.xh.hoist.cluster.ClusterResponse
import io.xh.hoist.cluster.ReplicatedValueEntry
import io.xh.hoist.util.Utils
import static io.xh.hoist.util.InstanceConfigUtils.getInstanceConfig

import static grails.util.Holders.grailsApplication

class ClusterConfig {

    /**
     * Name of Hazelcast cluster.
     *
     * This value identifies the cluster to attach to, create and is unique to this
     * application, version, and environment.
     *
     * To customize, override generateClusterName().
     */
    final String clusterName = generateClusterName()


    /**
     * Instance name of Hazelcast member.
     * To customize, override generateInstanceName().
     */
    final String instanceName = generateInstanceName()


    /**
     * Are multi-instance clusters enabled?
     *
     * Defaults to true.  Is set to false, Hoist will not create multi-instance clusters and may
     * use simpler in-memory  data-structures in place of their Hazelcast counterparts.  Use this
     * for applications that do not require multi-instance and do not wish to pay the serialization penalty of storing
     * shared data in Hazelcast.
     *
     * Applications and plug-ins may set this value explicitly via the `multiInstanceEnabled`
     * instance config, or override this method to implement additional logic.
     */
    boolean getMultiInstanceEnabled() {
        return getInstanceConfig('multiInstanceEnabled') !== 'false'
    }

    /**
     * Override this method to customize the cluster name of the Hazelcast cluster.
     */
    protected String generateClusterName() {
        def ret = Utils.appCode + '_' + Utils.appEnvironment + '_' + Utils.appVersion
        if (!multiInstanceEnabled) ret +=  '_' + UUID.randomUUID().toString().take(8)
        return ret
    }

    /**
     * Override this method to customize the instance name of the Hazelcast member.
     */
    protected String generateInstanceName() {
        UUID.randomUUID().toString().take(8)
    }

    /**
     * Produce configuration for the hazelcast cluster.
     *
     * Hoist uses simple Hazelcast's "multicast" cluster discovery by default.  While often
     * appropriate for local development, this may not be appropriate for your production
     * application and can be replaced here with alternative cluster discovery mechanisms.
     *
     * This method should also be used to specify custom configurations of distributed
     * hazelcast objects.
     *
     * Override this method to provide plugin or app specific configuration.
     */
    Config createConfig() {
        def ret = new Config()

        ret.instanceName = instanceName
        ret.clusterName = clusterName
        ret.memberAttributeConfig.setAttribute('instanceName', instanceName)

        ret.networkConfig.join.multicastConfig.enabled = true

        createDefaultConfigs(ret)
        createHibernateConfigs(ret)
        createServiceConfigs(ret)

        SubZero.useForClasses(ret, classesForKryo().toArray() as Class[])

        return ret
    }

    /**
     * Override this method to specify additional classes that should be serialized with Kryo
     * when being stored in Hazelcast structures.
     *
     * Kryo provides a more efficient serialization format and is recommended over default
     * Java serialization for objects being stored in Hazelcast.
     *
     * Note that objects being stored in a Hoist `ReplicatedValue` or cluster-enabled `Cache` will not
     * need their classes specified in this method.  These objects are always serialized via Kryo.
     */
    protected List<Class> classesForKryo() {
        return [ReplicatedValueEntry, Entry, ClusterResponse]
    }

    /**
     * Override this to create additional default configs in the application.
     */
    protected void createDefaultConfigs(Config config) {
        config.getMapConfig('default').with {
            statisticsEnabled = true
            inMemoryFormat = InMemoryFormat.OBJECT
            nearCacheConfig = new NearCacheConfig().setInMemoryFormat(InMemoryFormat.OBJECT)
        }
        config.getReplicatedMapConfig('default').with {
            statisticsEnabled = true
            inMemoryFormat = InMemoryFormat.OBJECT
        }
        config.getTopicConfig('default').with {
            statisticsEnabled = true
        }
        config.getSetConfig('default').with {
            statisticsEnabled = true
        }
        config.getCacheConfig('default').with {
            statisticsEnabled = true
            evictionConfig.maxSizePolicy = MaxSizePolicy.ENTRY_COUNT
            evictionConfig.evictionPolicy = EvictionPolicy.LRU
            evictionConfig.size = 5000
        }

        config.getCacheConfig('default-update-timestamps-region').with {
            evictionConfig = new EvictionConfig(evictionConfig).setSize(1000)
        }

        config.getCacheConfig('default-query-results-region').with {
            evictionConfig = new EvictionConfig(evictionConfig).setSize(1000)
        }
    }

    //------------------------
    // Implementation
    //------------------------
    private void createHibernateConfigs(Config config) {
        grailsApplication.domainClasses.each { GrailsClass gc ->
            Closure customizer = gc.getPropertyValue('cache') as Closure
            if (customizer) {
                // IMPORTANT -- We do an explicit clone, because wild card matching in hazelcast will
                // actually just return the *shared* config (?!), and never want to let app edit that.
                // Also need to explicitly clone the evictionConfig due to a probable Hz bug.
                def baseConfig = config.findCacheConfig(gc.fullName),
                    cacheConfig = new CacheSimpleConfig(baseConfig)
                cacheConfig.evictionConfig = new EvictionConfig(baseConfig.evictionConfig)
                customizer.delegate = cacheConfig
                customizer.resolveStrategy = Closure.DELEGATE_FIRST
                customizer(cacheConfig)
            }
        }
    }

    private void createServiceConfigs(Config config) {
        grailsApplication.serviceClasses.each { GrailsClass gc ->
            Map objs = gc.getPropertyValue('clusterConfigs')
            if (!objs) return
            objs.forEach {String key, List value ->
                def customizer = value[1] as Closure,
                    objConfig
                // IMPORTANT -- We do an explicit clone, because wild card matching in hazelcast will
                // actually just return the *shared* config (?!), and never want to let app edit that.
                switch (value[0]) {
                    case IMap:
                        objConfig = new MapConfig(config.findMapConfig(gc.fullName + '_' + key))
                        break
                    case ReplicatedMap:
                        objConfig = new ReplicatedMapConfig(config.findReplicatedMapConfig(gc.fullName + '_' + key))
                        break
                    case ISet:
                        objConfig = new SetConfig(config.findSetConfig(gc.fullName + '_' + key))
                        break
                    case ITopic:
                        objConfig = new TopicConfig(config.findTopicConfig(key))
                        break
                    default:
                        throw new RuntimeException('Unable to configure Cluster object')
                }
                customizer.delegate = objConfig
                customizer.resolveStrategy = Closure.DELEGATE_FIRST
                customizer(objConfig)
            }
        }
    }
}