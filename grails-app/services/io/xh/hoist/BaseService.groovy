/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2020 Extremely Heavy Industries Inc.
 */

package io.xh.hoist

import grails.async.Promise
import grails.async.Promises
import grails.events.bus.EventBusAware
import grails.util.GrailsClassUtils
import groovy.transform.CompileDynamic
import groovy.util.logging.Slf4j
import io.xh.hoist.exception.ExceptionRenderer
import io.xh.hoist.log.LogSupport
import io.xh.hoist.util.Timer
import io.xh.hoist.user.HoistUser
import io.xh.hoist.user.IdentityService
import org.springframework.beans.factory.DisposableBean

import java.util.concurrent.TimeUnit

import static grails.async.Promises.task
import static io.xh.hoist.util.DateTimeUtils.SECONDS

/**
 * Standard superclass for all Hoist and Application-level services.
 * Provides template methods for service lifecycle / state management plus support for user lookups.
 */
@Slf4j
abstract class BaseService implements LogSupport, DisposableBean, EventBusAware {

    IdentityService identityService
    ExceptionRenderer exceptionRenderer
    protected boolean _initialized = false
    private boolean _destroyed = false

    private final List<Timer> _timers = []

    /**
     * Initialize a collection of BaseServices in parallel.
     *
     * This is a blocking method.  Applications should make one more
     * calls to it to batch initialize their services in an appropriate order.
     *
     * @param services - BaseServices to initialize
     * @param timeout - maximum time to wait for each service to init.
     */
    static void parallelInit(List services, int timeout = 30 * SECONDS) {
        def allTasks = services.collect {svc ->
            task { svc.initialize(timeout) }
        }
        Promises.waitAll(allTasks)
    }

    /**
     * Initialize this service.
     *
     * Applications should be sure to call this method in their bootstrap,
     * either directly or indirect via BaseService.parallelInit.
     *
     * NOTE -- this method is designed to catch (and log) all exceptions,
     * in order to avoid preventing server startup.
     *
     * @param timeout
     * @param timeout - maximum time to wait for each service to init.
     */
    final void initialize(int timeout) {
        try {
            withInfo("Initializing") {
                task {
                    init()
                }.get(timeout, TimeUnit.MILLISECONDS)
                setupClearCachesConfigs()
                _initialized = true
            }
        } catch (Throwable t) {
            exceptionRenderer.handleException(t, this)
        }
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
        _timers << ret
        return ret
    }

    /**
     * Managed Subscription to a Grails Event.
     *
     * This method will catch (and log) any exceptions thrown by its handler closure.
     * This is important because the core grails EventBus.subscribe() will silently swallow
     * exceptions, and stop processing subsequent handlers.
     *
     * This subscription also avoids firing handlers on destroyed services. This is important in a
     * hot-reloading scenario where multiple instances of singleton services may be created.
     */
    protected void subscribe(String eventName, Closure c) {
        eventBus.subscribe(eventName) {Object... args ->
            if (destroyed) return
            try {
                instanceLog.debug("Receiving event '$eventName'")
                c.call(*args)
            } catch (Exception e) {
                logErrorCompact(instanceLog, "Exception handling event '$eventName':", e)
            }
        }
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
    void clearCaches() {}

    /**
     * Cleanup or release any service resources - e.g. cancel any timers.
     * Called by Spring on a clean shutdown of the application.
     */
    void destroy() {
        _timers.each {
            it.cancel()
        }
        _destroyed = true
    }

    //--------------------
    // Implemented methods
    //--------------------
    boolean isInitialized() {_initialized}
    boolean isDestroyed()   {_destroyed}

    protected HoistUser getUser() {
        identityService.user
    }

    protected String getUsername() {
        identityService.username
    }

    protected void setupClearCachesConfigs() {
        Set deps = new HashSet()
        for (Class clazz = getClass(); clazz; clazz = clazz.superclass) {
            def list = GrailsClassUtils.getStaticFieldValue(clazz, 'clearCachesConfigs')
            list.each {deps << it}
        }
        
        if (deps) {
            subscribe('xhConfigChanged') {Map ev ->
                if (deps.contains(ev.key)) clearCaches()
            }
        }
    }
}
