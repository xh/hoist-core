/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
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
import io.xh.hoist.cluster.ClusterService
import io.xh.hoist.cluster.ReplicatedValue
import io.xh.hoist.exception.ExceptionHandler
import io.xh.hoist.log.LogSupport
import io.xh.hoist.user.IdentitySupport
import io.xh.hoist.util.Timer
import io.xh.hoist.user.HoistUser
import io.xh.hoist.user.IdentityService
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean

import java.util.concurrent.TimeUnit

import static grails.async.Promises.task
import static io.xh.hoist.util.DateTimeUtils.SECONDS
import static io.xh.hoist.util.Utils.appContext
import static io.xh.hoist.util.Utils.getConfigService

/**
 * Standard superclass for all Hoist and Application-level services.
 * Provides template methods for service lifecycle / state management plus support for user lookups.
 * As an abstract class, BaseService must reside in src/main/groovy to allow Java compilation and
 * to ensure it is not itself instantiated as a Grails service.
 */
abstract class BaseService implements LogSupport, IdentitySupport, DisposableBean {

    IdentityService identityService
    ClusterService clusterService

    ExceptionHandler xhExceptionHandler

    Date initializedDate = null
    Date lastCachesCleared = null

    protected final List<Timer> timers = []

    private boolean _destroyed = false
    private ReplicatedMap _repValuesMap
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
        } catch (Throwable t) {
            xhExceptionHandler.handleException(exception: t, logTo: this)
        } finally {
            initializedDate = new Date();
        }
    }

    //-----------------------------------------------------------------
    // Distributed Resources
    // Use static reference to ClusterService to allow access pre-init.
    //------------------------------------------------------------------
    <K, V> IMap<K, V> getIMap(String id) {
        ClusterService.hzInstance.getMap(hzName(id))
    }

    <V> ISet<V> getISet(String id) {
        ClusterService.hzInstance.getSet(hzName(id))
    }

    <K, V> ReplicatedMap<K, V> getReplicatedMap(String id) {
        ClusterService.hzInstance.getReplicatedMap(hzName(id))
    }

    <T> ReplicatedValue<T> getReplicatedValue(String key) {
        new ReplicatedValue<T>(key, repValuesMap)
    }

    <M> ITopic<M> getTopic(String id) {
        ClusterService.hzInstance.getTopic(id)
    }

    /**
     * Create a new managed Timer bound to this service.
     * @param args - arguments appropriate for a Hoist Timer.
     */
    @CompileDynamic
    protected Timer createTimer(Map args) {
        args.owner = this
        if (!args.runFn && metaClass.respondsTo(this, 'onTimer')) {
            args.runFn = this.&onTimer
        }
        def ret = new Timer(args)
        timers << ret
        return ret
    }

    /**
     * Managed Subscription to a Grails Event.
     *
     * NOTE:  Use this method to subscribe to local Grails events on the given server
     * instance only.  To subscribe to cluster-wide topics, use 'subscribeToTopic' instead.
     *
     * This method will catch (and log) any exceptions thrown by its handler closure.
     * This is important because the core grails EventBus.subscribe() will silently swallow
     * exceptions, and stop processing subsequent handlers.
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
     * Managed Subscription to a cluster topic.
     *
     * NOTE:  Use this method to subscribe to cluster-wide topics. To subscribe to local
     * Grails events on this instance only, use subscribe instead.
     *
     * This method will catch (and log) any exceptions thrown by its handler closure.
     *
     * This subscription also avoids firing handlers on destroyed services. This is important in a
     * hot-reloading scenario where multiple instances of singleton services may be created.
     */
    protected void subscribeToTopic(Map config) {
        def topic = config.topic as String,
            onMessage = config.onMessage as Closure,
            masterOnly = config.masterOnly as Boolean


        getTopic(topic).addMessageListener { Message m ->
            if (destroyed || (masterOnly && !isMaster)) return
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
    /** Is this instance the master instance */
    protected boolean getIsMaster() {
        clusterService.isMaster
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
        timers.each {
            it.cancel()
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
    boolean isInitialized() {!!initializedDate}
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


    //------------------------
    // Internal implementation
    //------------------------
    protected String hzName(String key) {
        this.class.name + '_' + key
    }

    private ReplicatedMap getRepValuesMap() {
        _repValuesMap = _repValuesMap ?: getReplicatedMap('replicatedValues')
    }
}
