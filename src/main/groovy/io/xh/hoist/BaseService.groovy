/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2025 Extremely Heavy Industries Inc.
 */

package io.xh.hoist

import com.hazelcast.collection.ISet
import com.hazelcast.map.IMap
import com.hazelcast.replicatedmap.ReplicatedMap
import com.hazelcast.topic.ITopic
import com.hazelcast.topic.Message
import grails.async.Promises
import grails.util.GrailsClassUtils
import groovy.transform.CompileDynamic
import groovy.transform.NamedParam
import groovy.transform.NamedVariant
import io.xh.hoist.cache.Cache
import io.xh.hoist.cachedvalue.CachedValue
import io.xh.hoist.cluster.ClusterService
import io.xh.hoist.exception.ExceptionHandler
import io.xh.hoist.log.LogSupport
import io.xh.hoist.user.IdentitySupport
import io.xh.hoist.util.Timer
import io.xh.hoist.user.HoistUser
import io.xh.hoist.user.IdentityService
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

import static grails.async.Promises.task
import static io.xh.hoist.util.DateTimeUtils.SECONDS
import static io.xh.hoist.util.DateTimeUtils.MINUTES
import static io.xh.hoist.util.Utils.appContext
import static io.xh.hoist.util.Utils.getConfigService
import static io.xh.hoist.cluster.ClusterService.hzInstance

/**
 * Standard superclass for all Hoist and Application-level services.
 * Provides template methods for service lifecycle / state management plus support for user lookups.
 * As an abstract class, BaseService must reside in src/main/groovy to allow Java compilation and
 * to ensure it is not itself instantiated as a Grails service.
 *
 * BaseService also provides support for cluster aware state via factories to create
 * Hoist objects such as Cache, CachedValue, Timer, as well as raw Hazelcast distributed
 * data structures such as ReplicatedMap, ISet and IMap.  Objects created with these factories
 * will be associated with this service for the purposes of logging and management via the
 * Hoist admin console.
 */
abstract class BaseService implements LogSupport, IdentitySupport, DisposableBean {

    IdentityService identityService
    ClusterService clusterService

    ExceptionHandler xhExceptionHandler

    Date initializedDate = null
    Date lastCachesCleared = null

    // Caches, CachedValues and Timers and other distributed objects associated with this service
    protected final ConcurrentHashMap<String, Object> resources = new ConcurrentHashMap()

    private boolean _destroyed = false

    private final Logger _log = LoggerFactory.getLogger(this.class)


    /**
     * Initialize a collection of BaseServices in parallel.
     *
     * This is a blocking method.  Applications should make one or more calls to it to batch
     * initialize their services in an appropriate order.
     *
     * @param services - BaseServices to initialize
     * @param timeout - maximum time to wait for each service to init (in ms).
     */
    static void parallelInit(Collection<BaseService> services, Long timeout = 30 * SECONDS) {
        def allTasks = services.collect {svc ->
            task { svc.initialize(timeout) }
        }
        Promises.waitAll(allTasks)
    }

    /**
     * Initialize this service.
     *
     * Applications should be sure to call this method in their bootstrap, either directly or
     * via `BaseService.parallelInit()`.
     *
     * NOTE - this method catches (and logs) all exceptions to prevent a single from blocking server startup.
     *
     * @param timeout - maximum time to wait for each service to init (in ms).
     */
    final void initialize(Long timeout = 30 * SECONDS) {
        if (initializedDate) return
        try {
            withInfo("Initializing") {
                task {
                    init()
                }.get(timeout, TimeUnit.MILLISECONDS)
                setupClearCachesConfigs()
            }
        } catch (ExecutionException ee) {
            // Show the underlying init() exception instead of the ExecutionException
            xhExceptionHandler.handleException(exception: ee.cause, logTo: this)
        } catch (Throwable t) {
            xhExceptionHandler.handleException(exception: t, logTo: this)
        } finally {
            initializedDate = new Date()
        }
    }

    //-----------------------------------------------------------------
    // Distributed Resources
    //------------------------------------------------------------------
    /**
     * Create and return a reference to a Hazelcast IMap.
     *
     * @param name - must be unique across all Caches, Timers and distributed Hazelcast objects
     * associated with this service.
     */
    <K, V> IMap<K, V> createIMap(String name) {
        addResource(name, hzInstance.getMap(hzName(name)))
    }

    /**
     * Create and return a reference to a Hazelcast ISet.
     *
     * @param name - must be unique across all Caches, Timers and distributed Hazelcast objects
     * associated with this service.
     */
    <V> ISet<V> createISet(String name) {
        addResource(name, hzInstance.getSet(hzName(name)))
    }

    /**
     * Create and return a reference to a Hazelcast Replicated Map.
     *
     * @param name - must be unique across all Caches, Timers and distributed Hazelcast objects
     * associated with this service.
     */
     <K, V> ReplicatedMap<K, V> createReplicatedMap(String name) {
         addResource(name, hzInstance.getReplicatedMap(hzName(name)))
     }

    /**
     * Get a reference to an existing or new Hazelcast topic.
     * To subscribe to events fired by other services on a topic, use {@link #subscribeToTopic}.
     */
     <M> ITopic<M> getTopic(String id) {
        hzInstance.getTopic(id)
    }

    /**
     * Create a new managed {@link Timer} bound to this service.
     *
     * Note that the provided name must be unique across all Caches, Timers and distributed
     * Hazelcast objects associated with this service.
     */
    @CompileDynamic
    @NamedVariant
    Timer createTimer(
        @NamedParam(required = true) String name,
        @NamedParam Closure runFn = null,
        @NamedParam Boolean primaryOnly = false,
        @NamedParam Boolean runImmediatelyAndBlock = false,
        @NamedParam Object interval = null,
        @NamedParam Object timeout = 3 * MINUTES,
        @NamedParam Object delay = false,
        @NamedParam Long intervalUnits = 1,
        @NamedParam Long timeoutUnits = 1
    ) {
        if (!runFn) {
            if (metaClass.respondsTo(this, 'onTimer')) {
                runFn = this.&onTimer
            } else {
                throw new IllegalArgumentException('Must specify a runFn, or provide an onTimer() method on this service.')
            }
        }

        addResource(name,
            new Timer(
                name,
                this,
                runFn,
                primaryOnly,
                runImmediatelyAndBlock,
                interval,
                timeout,
                delay,
                intervalUnits,
                timeoutUnits
            )
        )
    }

    /**
     * Create a new {@link Cache} bound to this service.
     *
     * Note that the provided name must be unique across all Caches, Timers and distributed
     * Hazelcast objects associated with this service.
     */
    <K, V> Cache<K, V> createCache(Map mp) {
        // Cannot use @NamedVariant, as incompatible with generics. We'll still get run-time checks.
        addResource(mp.name as String, new Cache([*:mp, svc: this]))
    }

    /**
     * Create a new {@link CachedValue} bound to this service.
     *
     * Note that the provided name must be unique across all Caches, Timers and distributed
     * Hazelcast objects associated with this service.
     */
    <T> CachedValue<T> createCachedValue(Map mp) {
        // Cannot use @NamedVariant, as incompatible with generics. We'll still get run-time checks.
        addResource(mp.name as String, new CachedValue([*:mp, svc: this]))
    }

    /**
     * Create a managed subscription to events on the instance-local Grails event bus.
     *
     * NOTE: this method subscribes to Grails events on the current server instance only.
     * To subscribe to cluster-wide topics, use {@link #subscribeToTopic} instead.
     *
     * This method will catch (and log) any exceptions thrown by its handler closure.
     * This is important because the core Grails `EventBus.subscribe()` will silently swallow
     * exceptions and stop processing subsequent handlers.
     *
     * This subscription also avoids firing handlers on destroyed services. This is important in a
     * hot-reloading scenario where multiple instances of singleton services may be created.
     */
    protected void subscribe(String eventName, Closure c) {
       appContext.eventBus.subscribe(eventName) {Object... args ->
            if (destroyed) return
            try {
                logDebug("Receiving event '$eventName'")
                c.call(*args)
            } catch (Exception e) {
                logError("Exception handling event '$eventName'", e)
            }
        }
    }


    /**
     *
     * Create a managed subscription to a cluster topic.
     *
     * NOTE: this subscribes to cluster-wide topics. To subscribe to local Grails events on this
     * instance only, use {@link #subscribe} instead. That said, this is most likely the method you
     * want, as most pub/sub use cases should take multi-instance operation into account.
     *
     * This method will catch (and log) any exceptions thrown by its handler closure.
     *
     * This subscription also avoids firing handlers on destroyed services. This is important in a
     * hot-reloading scenario where multiple instances of singleton services may be created.
     */
    @NamedVariant
    protected void subscribeToTopic(
        @NamedParam(required = true) String topic,
        @NamedParam(required = true) Closure onMessage,
        @NamedParam Boolean primaryOnly = false
    ) {
        getTopic(topic).addMessageListener { Message m ->
            if (destroyed || (primaryOnly && !isPrimary)) return
            try {
                logDebug("Receiving message on topic '$topic'")
                if (onMessage.maximumNumberOfParameters == 1) {
                    onMessage.call(m.messageObject)
                } else {
                    onMessage.call(m.messageObject, m)
                }
            } catch (Exception e) {
                logError("Exception handling message on topic '$topic'", e)
            }
        }
    }

    //------------------
    // Cluster Support
    //------------------
    /** Is this instance the primary instance */
    protected boolean getIsPrimary() {
        clusterService.isPrimary
    }

    //-----------------------------------
    // Core template methods for override
    //-----------------------------------
    /**
     * Initialize the service, setting up any starting state or objects required for operation.
     */
    protected void init() {}

    /**
     * Clear or reset any service state. Can include but is not limited to clearing Cache objects - could also handle
     * resetting other stateful service objects such as HttpClients. The Hoist admin client provides a UI to call
     * this method on all BaseServices within a running application as an operational / troubleshooting tool.
     */
    void clearCaches() {
        lastCachesCleared = new Date()
    }

    /**
     * Cleanup or release any service resources - e.g. cancel any timers.
     * Called by Spring on a clean shutdown of the application.
     */
    void destroy() {
        resources.each { k, v ->
            if (v instanceof Timer) v.cancel()
        }
        _destroyed = true
    }

    /**
     * Return meta data about this service for troubleshooting and monitoring.
     * This data will be exposed via the Hoist admin client.
     *
     * Note that information about service timers and distributed objects does not need to be
     * included here and will be automatically included by the framework.
     */
    Map getAdminStats(){[:]}

    List<String> getComparisonFields(){[]}

    /**
     * Return a map of specified config values, appropriate for including in
     * implementations of getAdminStats().
     */
    protected Map configForAdminStats(String... names) {
        getConfigService().getForAdminStats(names)
    }

    //--------------------
    // Implemented methods
    //--------------------
    boolean isInitialized() {initializedDate != null}
    boolean isDestroyed()   {_destroyed}

    HoistUser getUser()         {identityService.user}
    String getUsername()        {identityService.username}
    HoistUser getAuthUser()     {identityService.authUser}
    String getAuthUsername()    {identityService.authUsername}

    protected void setupClearCachesConfigs() {
        Set deps = new HashSet()
        for (Class clazz = getClass(); clazz; clazz = clazz.superclass) {
            def list = GrailsClassUtils.getStaticFieldValue(clazz, 'clearCachesConfigs')
            list.each {deps << it}
        }

        if (deps) {
            subscribeToTopic(
                topic: 'xhConfigChanged',
                onMessage: { Map msg ->
                    def key = msg.key
                    if (deps.contains(key)) {
                        logInfo("Clearing caches due to config change", key)
                        clearCaches()
                    }
                }
            )
        }
    }

    // Provide cached logger to LogSupport for possible performance benefit
    Logger getInstanceLog() { _log }

    /**
     * Generate a name for a resource, appropriate for Hazelcast.
     * Note that this allows us to group all Hazelcast resources by Service
     *
     * Not typically called directly by applications.  Applications should aim to create
     * Hazelcast distributed objects using the methods in this class.
     */
    String hzName(String name) {
       hzName(name, this.class)
    }

    static String hzName(String name, Class clazz) {
        "${clazz.name}[$name]"
    }

    //------------------------
    // Internal implementation
    //------------------------
    private <T> T addResource(String name, T resource) {
        if (!name || resources.containsKey(name)) {
            def msg = 'Service resource requires a unique name. '
            if (name) msg += "Name '$name' already used on this service."
            throw new RuntimeException(msg)
        }
        resources[name] = resource
        return resource
    }
}
