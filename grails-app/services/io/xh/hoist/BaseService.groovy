/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2019 Extremely Heavy Industries Inc.
 */

package io.xh.hoist

import grails.events.bus.EventBusAware
import grails.util.GrailsClassUtils
import groovy.transform.CompileDynamic
import groovy.util.logging.Slf4j
import io.reactivex.Observable
import io.xh.hoist.async.AsyncSupport
import io.xh.hoist.log.LogSupport
import io.xh.hoist.util.Timer
import io.xh.hoist.user.HoistUser
import io.xh.hoist.user.IdentityService
import org.springframework.beans.factory.DisposableBean

import static io.xh.hoist.rx.ReactiveUtils.createObservable
import static io.xh.hoist.util.DateTimeUtils.SECONDS
import static io.xh.hoist.util.Utils.withNewSession

/**
 * Standard superclass for all Hoist and Application-level services.
 * Provides template methods for service lifecycle and state management plus support for user look-ups.
 */
@Slf4j
abstract class BaseService implements LogSupport, AsyncSupport, DisposableBean, EventBusAware {

    IdentityService identityService
    private boolean _initialized = false
    private boolean _destroyed = false

    private final List<Timer> _timers = []

    /**
     * Initialize a collection of BaseServices in parallel.
     * Hoist uses this method to initialize its own core services, and Applications may make one more more calls
     * to initialize their services in an order that suits their needs.
     *
     * @param services - BaseServices to initialize
     * @param timeout - maximum time to wait for each service to init.
     */
    static void parallelInit(List services, int timeout = 30 * SECONDS) {
        def initService = {svc ->
            def name = svc.class.simpleName
            createObservable(timeout: timeout) {
                withShortInfo(log, "Initialized service $name") {
                    svc.init()
                }
            }.onErrorReturn {e ->
                log.info("Failed to initialize service $name: ${e.message}")
                false
            }
        }

        Observable.fromIterable(services)
                .flatMap(initService)
                .blockingSubscribe()
    }
    

    /**
     * Create a Timer associated with this service.
     *
     * This method will create a new managed Timer bound to this service.
     * @param args, arguments appropriate for ServiceUpdater
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
     * This subscription also avoids firing handlers on destroyed services.  This is important in a hot-reloading
     * scenario where multiple instances of singleton services may be created.
     */
    protected void subscribe(String eventName, Closure c) {
        eventBus.subscribe(eventName) {Object... args ->
            if (destroyed) return
            try {
                instanceLog.debug("Receiving event '$eventName'")
                c.call(*args)
            } catch (Exception e) {
                instanceLog.error("Exception handling event '$eventName':", e)
            }
        }
    }

    /**
     * Managed Subscription to a Grails Event with a provided hibernate session.
     *
     * See subscribe() for more information.
     */
    protected void subscribeWithSession(String eventName, Closure c) {
        subscribe(eventName) {Object... args ->
            withNewSession {
                c.call(*args)
            }
        }
    }

    //-----------------------------------
    // Core template methods for override
    //-----------------------------------
    /**
     * Initialize the service, setting up any starting state or objects required for operation. Must be called in
     * Application Bootstrap either directly or by inclusion in a call to parallelInit().
     * Hoist will also call this method during development-time hot reloading, if necessary.
     */
    void init() {
        setupClearCachesConfigs()
        _initialized = true
    }

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
        identityService.getUser()
    }

    protected String getUsername() {
        getUser()?.username
    }

    private void setupClearCachesConfigs() {
        Set deps = new HashSet()
        for (Class clazz = getClass(); clazz; clazz = clazz.superclass) {
            def list = GrailsClassUtils.getStaticFieldValue(clazz, 'clearCachesConfigs')
            list.each {deps << it}
        }
        
        if (deps) {
            subscribe('xhConfigChanged') {Map ev ->
                if (deps.contains(ev.key)) {
                    withNewSession {clearCaches()}
                }
            }
        }
    }
    
}
