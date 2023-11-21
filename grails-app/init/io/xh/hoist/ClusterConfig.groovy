package io.xh.hoist

import com.hazelcast.config.CacheSimpleConfig
import com.hazelcast.config.Config
import com.hazelcast.config.EvictionPolicy
import com.hazelcast.config.MaxSizePolicy
import io.xh.hoist.util.Utils

class ClusterConfig {

    /**
     * Name of Hazelcast cluster.
     *
     * This value identifies the cluster to attach to, create and is unique to this
     * application, version, and environment.
     */
    String getClusterName() {
        Utils.appCode + '_' + Utils.appEnvironment + '_' + Utils.appVersion
    }

    /**
     * Name of this instance.
     *
     * A randomly chosen unique identifier.
     */
    String getInstanceName() {
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

        // Specify core identity of the instance...
        ret.instanceName = getInstanceName()
        ret.clusterName = getClusterName()
        ret.memberAttributeConfig.setAttribute('instanceName', ret.instanceName)

        // ...and add Hibernate caches and default networking
        createCacheConfigs().each { ret.addCacheConfig(it) }
        ret.networkConfig.join.multicastConfig.enabled = true

        return ret
    }

    /**
     * The list of hibernate caches to configure for this application.
     *
     * Override this method to configure additional caches.
     */
    protected List<CacheSimpleConfig> createCacheConfigs() {
        [
            hibernateCache('default-update-timestamps-region') {
                it.evictionConfig.size = 1000
            },
            hibernateCache('default-query-results-region') {
                it.evictionConfig.size = 1000
            },
            hibernateCache('io.xh.hoist.clienterror.ClientError'),
            hibernateCache('io.xh.hoist.config.AppConfig'),
            hibernateCache('io.xh.hoist.feedback.Feedback'),
            hibernateCache('io.xh.hoist.jsonblob.JsonBlob'),
            hibernateCache('io.xh.hoist.log.LogLevel'),
            hibernateCache('io.xh.hoist.monitor.Monitor'),
            hibernateCache('io.xh.hoist.pref.Preference'),
            hibernateCache('io.xh.hoist.pref.UserPreference'),
            hibernateCache('io.xh.hoist.track.TrackLog') {
                it.evictionConfig.size = 20000
            }
        ]
    }

    /**
     * Create a hibernate cache to be used for this application.  Typically used in
     * implementations of createCacheConfigs().
     *
     * Additional properties for the returned cache may be provided via an optional closure.
     *
     * Override this method to change the default configuration for all hibernate caches used by
     * this application.
     */
    protected CacheSimpleConfig hibernateCache(String name, Closure closure = null) {
        def ret = new CacheSimpleConfig(name)
        ret.statisticsEnabled = true
        ret.evictionConfig.maxSizePolicy = MaxSizePolicy.ENTRY_COUNT
        ret.evictionConfig.evictionPolicy = EvictionPolicy.LRU
        ret.evictionConfig.size = 5000
        closure?.call(ret)
        return ret
    }
}