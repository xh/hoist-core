package io.xh.hoist

import com.hazelcast.config.Config
import com.hazelcast.config.EvictionConfig
import com.hazelcast.config.EvictionPolicy
import com.hazelcast.config.InMemoryFormat
import com.hazelcast.config.NearCacheConfig
import com.hazelcast.map.IMap
import com.hazelcast.replicatedmap.ReplicatedMap
import com.hazelcast.topic.ITopic
import com.hazelcast.collection.ISet
import com.hazelcast.config.MaxSizePolicy
import grails.core.GrailsClass
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
     * Defaults to true.  If set to false, Hoist will not create multi-instance clusters and may
     * use simpler in-memory data-structures in place of their Hazelcast counterparts.  Use this
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
        createCachedValueConfigs(ret)

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
     * Override this to create additional default Hazelcast configs in the application.
     *
     * Note that Hoist also introduces two properties for declarative configuration:
     *
     * - a static 'cache' property on Grails domain objects to customize associated
     * Hibernate caches. See toolbox's `Phase` object for examples.
     *
     * - a static 'configureCluster' property on Grails Service to allow services to provide
     * configuration for any custom hazelcast structures that they will user.  For example,
     * a service may configure a custom collection as follows:
     *
     *      static configureCluster = { Config c ->
     *           c.getMapConfig(hzName('myCollection', this)).with {
     *               evictionConfig.size = 100
     *           }
     *       }
     *     private IMap<String, Map> myCollection = createIMap('myCollection')
     */
    protected void createDefaultConfigs(Config config) {

        config.setProperty('hazelcast.logging.type', 'slf4j')

        // Hoist core will orchestrate hz shutdown from its own hook: See HoistCoreGrailsPlugin
        config.setProperty('hazelcast.shutdownhook.enabled', 'false')

        config.getMapConfig('default').with {
            statisticsEnabled = true
            inMemoryFormat = InMemoryFormat.OBJECT
            nearCacheConfig = new NearCacheConfig()
            nearCacheConfig.inMemoryFormat = InMemoryFormat.OBJECT
            nearCacheConfig.serializeKeys = true // See https://github.com/hazelcast/hazelcast/issues/19714
        }
        config.getReplicatedMapConfig('default').with {
            statisticsEnabled = true
            inMemoryFormat = InMemoryFormat.OBJECT
        }
        config.getTopicConfig('default').with {
            statisticsEnabled = true
        }
        config.getReliableTopicConfig('default').with {
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
            evictionConfig = new EvictionConfig(evictionConfig) // workaround - hz does not clone
            evictionConfig.size = 1000
        }

        config.getCacheConfig('default-query-results-region').with {
            evictionConfig = new EvictionConfig(evictionConfig) // workaround - hz does not clone
            evictionConfig.size = 10000
        }
    }

    //------------------------
    // Implementation
    //------------------------
    private void createHibernateConfigs(Config config) {
        grailsApplication.domainClasses.each { GrailsClass gc ->
            // Pre-access cache config for all domain classes to ensure we capture the common 'default'
            // (not clear why this is needed -- but hibernate would otherwise create these differently)
            def configs = [
                // 1) Main 2nd-level entity cache
                config.getCacheConfig(gc.fullName),
                // 2) any related collection caches
                config.getCacheConfig(gc.fullName + '.*')
            ]

            // Apply any app customization specified by new static prop introduced by Hoist
            // note we apply the same for both the entity cache [1] and any collection caches [2].
            Closure customizer = gc.getPropertyValue('cache') as Closure
            if (customizer) {
                configs.each { cfg ->
                    cfg.evictionConfig = new EvictionConfig(cfg.evictionConfig) // workaround - hz does not clone
                    customizer.delegate = cfg
                    customizer.resolveStrategy = Closure.DELEGATE_FIRST
                    customizer(cfg)
                }
            }
        }
    }

    private void createServiceConfigs(Config config) {
        // Ad-Hoc per service configuration, via static closure
        grailsApplication.serviceClasses.each { GrailsClass gc ->
            def customizer = gc.getPropertyValue('configureCluster') as Closure
            customizer?.call(config)
        }
    }

    private void createCachedValueConfigs(Config config) {
        config.getReliableTopicConfig('xhcachedvalue.*').with {
            readBatchSize = 1
        }
        config.getRingbufferConfig('xhcachedvalue.*').with {
            inMemoryFormat = InMemoryFormat.OBJECT
            capacity = 1
        }
    }
}