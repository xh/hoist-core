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
import io.xh.hoist.cluster.ClusterService
import io.xh.hoist.kryo.KryoSupport

import static io.xh.hoist.util.InstanceConfigUtils.appEnvironment
import static io.xh.hoist.util.InstanceConfigUtils.getInstanceConfig

import static grails.util.Holders.grailsApplication
import static io.xh.hoist.util.Utils.appBuild
import static io.xh.hoist.util.Utils.appCode
import static io.xh.hoist.util.Utils.appVersion
import static io.xh.hoist.util.Utils.isLocalDevelopment
import static java.util.UUID.randomUUID

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
     * for applications that do not require multi-instance and do not wish to pay the serialization
     * penalty of storing shared data in Hazelcast.
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
        List ret = [appCode, appEnvironment, appVersion]
        if (appVersion.contains('SNAPSHOT') && appBuild != 'UNKNOWN') ret << appBuild
        if (isLocalDevelopment) ret << System.getProperty('user.name')
        if (!multiInstanceEnabled) ret << randomUUID().toString().take(8)
        return ret.join('-')
    }

    /**
     * Override this method to customize the instance name of the Hazelcast member.
     */
    protected String generateInstanceName() {
        randomUUID().toString().take(8)
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

        createNetworkConfig(ret)

        createDefaultConfigs(ret)
        createHibernateConfigs(ret)
        createServiceConfigs(ret)

        KryoSupport.setAsGlobalSerializer(ret)

        return ret
    }

    /**
     * Create Hazelcast network Config for this application.
     *
     * Override this method to specify custom configuration for your environment.
     * This is especially important for customizing the "join" configuration used
     * by Hazelcast for autodiscovery.  Note that if `multiInstanceEnabled` is false,
     * any custom join code can be skipped.
     */
    protected void createNetworkConfig(Config config) {

    }

    /**
     * Override this to create additional default configs in the application.
     */
    protected void createDefaultConfigs(Config config) {
        config.getMapConfig('default').with {
            statisticsEnabled = true
            inMemoryFormat = InMemoryFormat.OBJECT
            /** Setting serializeKeys=true due to bug: https://github.com/hazelcast/hazelcast/issues/19714 */
            nearCacheConfig = new NearCacheConfig().setInMemoryFormat(InMemoryFormat.OBJECT).setSerializeKeys(true)
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