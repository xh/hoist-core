/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.test

import grails.core.GrailsApplication
import grails.util.Holders
import groovy.transform.CompileStatic
import io.xh.hoist.BaseService
import io.xh.hoist.cache.Cache
import io.xh.hoist.environment.EnvironmentService
import io.xh.hoist.exception.ExceptionHandler
import io.xh.hoist.user.HoistIdentity
import io.xh.hoist.user.HoistUser
import io.xh.hoist.user.IdentityService
import org.codehaus.groovy.runtime.InvokerHelper
import org.grails.core.support.GrailsApplicationDiscoveryStrategy
import org.springframework.context.ApplicationContext
import org.springframework.context.support.StaticApplicationContext

import java.beans.Introspector
import java.lang.reflect.Modifier

/**
 * A lightweight stand-in for the Hoist/Grails application context, allowing services and other
 * framework code to run in a plain unit test with no running application.
 *
 * Almost everything in Hoist - logging, identity, soft config, cluster status - looks up its
 * collaborators through static `Utils` accessors backed by the Grails application context.
 * Installing a `HoistTestContext` registers a small Spring context with Grails' `Holders` so those
 * lookups succeed, populated with test-friendly implementations of the core framework beans:
 *
 *  - `configService`: a {@link TestConfigService}, set values directly in code
 *  - `userService`: a {@link TestUserService}, holding in-memory users
 *  - `identityService`: the real `IdentityService`, so `user`/`username` on a service under test
 *    resolve exactly as in production, driven by {@link #loginAs}
 *  - `clusterService`: a {@link TestClusterService}, primary by default
 *  - `xhExceptionHandler`: the default `ExceptionHandler`
 *
 * Most specs should extend {@link HoistSpec} or use {@link HoistTest}, which install and close a
 * fresh context around each feature. It can also be driven directly:
 *
 * <pre>
 * def hoist = HoistTestContext.install()
 * try {
 *     hoist.configService.set('maxRows', 10)
 *     def svc = hoist.createService(MyService)
 *     hoist.loginAs('alice')
 *     ...
 * } finally {
 *     hoist.close()
 * }
 * </pre>
 *
 * Caveats:
 *  - The current context and its thread identity are process-wide, so this is not safe for specs
 *    executed in parallel within the same JVM.
 *  - Identity is bound to the calling thread - `task {}` workers started by a service do not
 *    inherit it.
 *  - If another running application context (e.g. from Grails testing support) is registered with
 *    `Holders` ahead of this one, that context wins the `Utils` lookups. Use its own bean
 *    registration in that case.
 *  - Distributed structures (`createIMap`, `createReplicatedMap`, topics) require Hazelcast, which
 *    is not started. Caches, CachedValues and Timers fall back to local implementations.
 */
@CompileStatic
class HoistTestContext implements AutoCloseable {

    private static HoistTestContext current
    /** Soft config for this context - set values here. */
    final TestConfigService configService

    /** In-memory users for this context - see {@link #loginAs}. */
    final TestUserService userService

    /** The real framework IdentityService, wired to this context's users. */
    final IdentityService identityService

    /** Simulated cluster status, primary by default. */
    final TestClusterService clusterService

    private final StaticApplicationContext context = new StaticApplicationContext()
    private final List<BaseService> createdServices = []

    private HoistTestContext() {
        context.refresh()   // Holders only considers a context that reports as running.

        configService = new TestConfigService()
        userService = new TestUserService()
        identityService = new IdentityService()
        clusterService = new TestClusterService()

        registerBean('configService', configService)
        registerBean('userService', userService)
        registerBean('identityService', identityService)
        registerBean('clusterService', clusterService)
        registerBean('xhExceptionHandler', new ExceptionHandler())

        [configService, userService, identityService, clusterService].each { autowire(it) }
    }

    //-------------------------
    // Installation
    //-------------------------
    /**
     * Create a new context and make it current, so that `Utils` lookups resolve against it. Any
     * previously installed context is closed first.
     */
    static synchronized HoistTestContext install() {
        current?.close()
        def ret = new HoistTestContext()
        current = ret

        // Register a delegating strategy unless lookups already resolve here. Re-checked on every
        // install, as Holders.clear() (e.g. from Grails testing support cleanup) drops strategies.
        if (!Holders.findApplicationContext().is(ret.context)) {
            Holders.addApplicationDiscoveryStrategy(new CurrentContextStrategy())
        }
        ret
    }

    /** The currently installed context, or null if none. */
    static HoistTestContext getCurrent() {
        current
    }

    //-------------------------
    // Beans and services
    //-------------------------
    /** The underlying Spring context holding this context's beans. */
    ApplicationContext getApplicationContext() {
        context
    }

    /**
     * Register a bean by name, replacing any existing bean of the same name. The name should match
     * the property name that services use to inject it (e.g. `'myOtherService'`) as well as any
     * `Utils` accessor, if applicable (e.g. `'authenticationService'`, `'roleService'`).
     */
    HoistTestContext registerBean(String name, Object bean) {
        def factory = context.defaultListableBeanFactory
        if (factory.containsSingleton(name)) factory.destroySingleton(name)
        factory.registerSingleton(name, bean)
        this
    }

    /** The registered bean of the given name, or null if none. */
    <T> T getBean(String name) {
        context.containsBean(name) ? (T) context.getBean(name) : null
    }

    /**
     * Inject registered beans into the properties of the target, matching by property name - as
     * Grails does for real services. Only null properties whose type is compatible with the bean
     * are set, so a service with an app-specific type for a bean this context does not provide
     * is left untouched.
     */
    <T> T autowire(T target) {
        def metaClass = InvokerHelper.getMetaClass(target)
        metaClass.properties.each { MetaProperty prop ->
            if (!(prop instanceof MetaBeanProperty) || Modifier.isStatic(prop.modifiers)) return
            def beanProp = (MetaBeanProperty) prop
            if (!beanProp.setter || !context.containsBean(prop.name)) return

            def bean = context.getBean(prop.name)
            if (prop.type.isInstance(bean) && beanProp.getProperty(target) == null) {
                beanProp.setProperty(target, bean)
            }
        }
        target
    }

    /**
     * Create a service: instantiate it (so field initializers, e.g. `createCache()`, run), inject
     * registered beans by name, apply any supplied property values, and register it as a bean
     * named for its class (e.g. `WeatherService` as `weatherService`) so later services can
     * inject it. The service is `destroy()`ed when this context closes.
     *
     * Note this does not call `init()` or `initialize()` - call `init()` explicitly if the
     * service's startup logic should run.
     *
     * @param clazz the service class, which must have a no-arg constructor
     * @param props property values to set after injection, e.g. to supply a mock collaborator
     */
    <T extends BaseService> T createService(Class<T> clazz, Map<String, ?> props = [:]) {
        T svc = clazz.getDeclaredConstructor().newInstance()
        autowire(svc)
        props.each { k, v -> InvokerHelper.setProperty(svc, k, v) }
        createdServices << svc
        registerBean(Introspector.decapitalize(clazz.simpleName), svc)
        svc
    }

    //-------------------------
    // Identity
    //-------------------------
    /** Add the user to this context's users and make them the logged-in user on this thread. */
    HoistUser loginAs(HoistUser user) {
        userService.add(user)
        identityService.installThreadIdentity(new HoistIdentity(user.username, user.username))
        user
    }

    /** Log in as the user of the given name, creating a {@link TestUser} with no roles if needed. */
    HoistUser loginAs(String username) {
        loginAs(userService.find(username) ?: new TestUser(username))
    }

    /**
     * Simulate an impersonation session, in which `authUsername` is logged in but the app
     * appears to be running as `apparentUsername`. Both users should be known to `userService`.
     */
    void impersonate(String apparentUsername, String authUsername) {
        identityService.installThreadIdentity(new HoistIdentity(apparentUsername, authUsername))
    }

    /** Clear the identity on this thread, as for an unauthenticated request. */
    void logout() {
        identityService.installThreadIdentity(null)
    }

    /** Run the closure logged in as the given user, then restore whatever identity was active. */
    <T> T withUser(HoistUser user, Closure<T> closure) {
        def previous = identityService.threadIdentity.get()
        try {
            loginAs(user)
            return closure.call()
        } finally {
            identityService.installThreadIdentity(previous)
        }
    }

    //-------------------------
    // Other framework support
    //-------------------------
    /**
     * Set the application time zone, as read by `DateTimeUtils.appTimeZone` and friends, by
     * registering the real `EnvironmentService` and setting its `xhAppTimeZone` soft config.
     */
    HoistTestContext setAppTimeZone(String zoneId) {
        configService.set('xhAppTimeZone', zoneId)
        def envService = getBean('environmentService')
        if (envService instanceof EnvironmentService) {
            ((EnvironmentService) envService).clearCaches()
        } else {
            createService(EnvironmentService)
        }
        this
    }

    //-------------------------
    // Lifecycle
    //-------------------------
    /**
     * Destroy services created via {@link #createService}, clear the thread identity, close the
     * context and, if it is the current context, uninstall it. Safe to call more than once.
     */
    @Override
    void close() {
        synchronized (HoistTestContext) {
            createdServices.reverse().each {
                try {
                    cancelCacheTimers(it)
                    it.destroy()
                } catch (Throwable ignored) {
                    // best-effort cleanup
                }
            }
            createdServices.clear()
            identityService.installThreadIdentity(null)
            if (context.active) context.close()
            if (current.is(this)) current = null
        }
    }

    //-------------------------
    // Implementation
    //-------------------------
    // BaseService.destroy() cancels its Timers but not the internal cull Timer of each Cache, which
    // would otherwise leave a live thread behind per Cache. Reach in and cancel them - best effort.
    private static void cancelCacheTimers(BaseService svc) {
        try {
            def resources = (Map) InvokerHelper.getProperty(svc, 'resources')
            resources.values().findAll { it instanceof Cache }.each {
                def field = Cache.getDeclaredField('cullTimer')
                field.accessible = true
                ((io.xh.hoist.util.Timer) field.get(it))?.cancel()
            }
        } catch (Throwable ignored) {
            // best-effort cleanup
        }
    }

    private static class CurrentContextStrategy implements GrailsApplicationDiscoveryStrategy {
        GrailsApplication findGrailsApplication() { null }
        ApplicationContext findApplicationContext() { HoistTestContext.current?.applicationContext }
    }
}
