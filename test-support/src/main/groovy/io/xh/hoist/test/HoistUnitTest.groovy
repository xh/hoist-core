/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.test

import grails.util.GrailsNameUtils
import groovy.transform.CompileDynamic
import groovy.transform.CompileStatic
import io.xh.hoist.environment.EnvironmentService
import io.xh.hoist.exception.ExceptionHandler
import io.xh.hoist.test.fakes.*
import io.xh.hoist.user.HoistIdentity
import io.xh.hoist.user.HoistUser
import io.xh.hoist.user.IdentityService
import io.xh.hoist.util.Utils
import org.grails.testing.GrailsUnitTest
import org.grails.testing.ParameterizedGrailsUnitTest

/**
 * Grails unit-test trait that adds the Hoist framework services that Hoist code expects to find
 * in the application context.
 *
 * Hoist services log via `LogSupport`, create caches and timers via `BaseService`, read soft
 * config and check the current user - all of which resolve framework services such as
 * `configService` and `identityService` from the Spring context. This trait builds on the
 * standard Grails test context (see `GrailsUnitTest`) and, before each feature, registers the
 * following beans - each autowired by name, and each only if the spec has not already defined a
 * bean of the same name (e.g. in `doWithSpring()`):
 *
 * | Bean | Class |
 * |---|---|
 * | `configService` | {@link TestConfigService} |
 * | `userService` | {@link TestUserService} |
 * | `identityService` | `IdentityService` |
 * | `clusterService` | {@link TestClusterService} |
 * | `trackService` | {@link TestTrackService} |
 * | `emailService` | {@link TestEmailService} |
 * | `xhExceptionHandler` | `ExceptionHandler` |
 *
 * Before each feature, config values, users, tracked entries and sent emails are cleared and
 * the instance is set back to primary. After each feature, the thread identity is cleared.
 *
 * Combine with the standard Grails traits to test an artefact:
 *
 * <pre>
 * class WeatherServiceSpec extends Specification implements ServiceUnitTest&lt;WeatherService&gt;, HoistUnitTest {
 *
 *     def 'reads api key from soft config'() {
 *         given:
 *         testConfigService.set('weatherApiKey', 'abc')
 *         loginAs(new TestUser('alice', ['HOIST_ADMIN']))
 *
 *         expect:
 *         service.apiKey == 'abc'
 *         service.user.isHoistAdmin
 *     }
 * }
 * </pre>
 *
 * The per-feature setup is applied by {@link HoistSpockGlobalExtension}, which is registered
 * automatically when `hoist-core-test` is on the test classpath.
 */
@CompileStatic
trait HoistUnitTest extends GrailsUnitTest {

    //------------------
    // Framework services
    //------------------
    /** The soft-config service - set config values for the code under test here. */
    TestConfigService getTestConfigService() {
        applicationContext.getBean('configService', TestConfigService)
    }

    /** The user service - add users here, or via {@link #loginAs}. */
    TestUserService getTestUserService() {
        applicationContext.getBean('userService', TestUserService)
    }

    /** The cluster service - set `primaryInstance = false` to test secondary-instance behavior. */
    TestClusterService getTestClusterService() {
        applicationContext.getBean('clusterService', TestClusterService)
    }

    /** The framework identity service, which resolves the current user from the thread identity. */
    IdentityService getIdentityService() {
        applicationContext.getBean('identityService', IdentityService)
    }

    /** The activity tracking service - assert on `tracked` / `lastTracked` here. */
    TestTrackService getTestTrackService() {
        applicationContext.getBean('trackService', TestTrackService)
    }

    /** The email service - assert on `sent` / `lastSent` here. */
    TestEmailService getTestEmailService() {
        applicationContext.getBean('emailService', TestEmailService)
    }

    /**
     * Register a service class as a bean, autowired by name like a Grails service, and return the
     * instance. Use for collaborators of the class under test, or for code that is not tested via
     * `ServiceUnitTest`. The bean is named for its class (e.g. `WeatherService` as
     * `weatherService`), so other beans can inject it.
     *
     * The service's `init()` is not called - call it explicitly if its startup logic should run.
     */
    <T> T defineService(Class<T> serviceClass) {
        String beanName = GrailsNameUtils.getPropertyName(serviceClass)
        defineAutowiredBeans([(beanName): (Class) serviceClass])
        applicationContext.getBean(beanName, serviceClass)
    }

    //------------------
    // Identity
    //------------------
    /** Add the user to {@link #getTestUserService} and make them the logged-in user on this thread. */
    HoistUser loginAs(HoistUser user) {
        testUserService.add(user)
        identityService.installThreadIdentity(new HoistIdentity(user.username, user.username))
        user
    }

    /** Log in as the user of the given name, creating a {@link TestUser} with no roles if needed. */
    HoistUser loginAs(String username) {
        loginAs(testUserService.find(username) ?: new TestUser(username))
    }

    /**
     * Simulate an impersonation session, in which `authUsername` is logged in but the app is
     * running as `apparentUsername`. Both users should be known to {@link #getTestUserService}.
     */
    void impersonate(String apparentUsername, String authUsername) {
        identityService.installThreadIdentity(new HoistIdentity(apparentUsername, authUsername))
    }

    /** Clear the identity on this thread, as for an unauthenticated request. */
    void logout() {
        identityService.installThreadIdentity(null)
    }

    /** Run the closure logged in as the given user, then restore whatever identity was active. */
    def <T> T withUser(HoistUser user, Closure<T> closure) {
        def previous = identityService.threadIdentity.get()
        try {
            loginAs(user)
            return closure.call()
        } finally {
            identityService.installThreadIdentity(previous)
        }
    }

    //------------------
    // Other framework support
    //------------------
    /**
     * Set the application time zone, as read by `DateTimeUtils.appTimeZone` and related methods.
     * Registers an `EnvironmentService` if none exists and sets its `xhAppTimeZone` soft config.
     */
    void useAppTimeZone(String zoneId) {
        testConfigService.set('xhAppTimeZone', zoneId)
        if (applicationContext.containsBean('environmentService')) {
            applicationContext.getBean('environmentService', EnvironmentService).clearCaches()
        } else {
            defineService(EnvironmentService)
        }
    }

    //------------------
    // Implementation - called by HoistSpockGlobalExtension
    //------------------
    /** Register any missing framework beans and reset their state. Called before each feature. */
    void setupHoistUnitTest() {
        // Stand in for HoistCoreGrailsPlugin, which installs these in a running app.
        Utils.setGrailsApplication(grailsApplication)
        Utils.setAppContext(applicationContext)

        Map<String, Class> missing = FRAMEWORK_BEANS.findAll { name, clazz -> !applicationContext.containsBean(name) }
        if (missing) defineAutowiredBeans(missing)

        // When the artefact under test *is* one of the framework beans (e.g. `ServiceUnitTest<ConfigService>`
        // in hoist-core's own specs), Grails redefines that bean on first access to `service`, and
        // Spring's dependent-bean cascade then destroys and recreates every bean autowired with it -
        // including identityService, losing any identity set via loginAs(). Create it up front so the
        // beans a feature sees are the ones it keeps. Other artefacts are created lazily, as Grails does by default.
        if (this instanceof ParameterizedGrailsUnitTest) {
            def parameterized = (ParameterizedGrailsUnitTest) this
            if (FRAMEWORK_BEANS.containsKey(GrailsNameUtils.getPropertyName(parameterized.typeUnderTest))) {
                parameterized.artefactInstance
            }
        }

        def configService = applicationContext.getBean('configService')
        if (configService instanceof TestConfigService) configService.clear()
        def userService = applicationContext.getBean('userService')
        if (userService instanceof TestUserService) userService.clear()
        def clusterService = applicationContext.getBean('clusterService')
        if (clusterService instanceof TestClusterService) clusterService.primaryInstance = true
        def trackService = applicationContext.getBean('trackService')
        if (trackService instanceof TestTrackService) trackService.clear()
        def emailService = applicationContext.getBean('emailService')
        if (emailService instanceof TestEmailService) emailService.clear()
        identityService.installThreadIdentity(null)
    }

    /** Clear the thread identity. Called after each feature. */
    void cleanupHoistUnitTest() {
        if (applicationContext.containsBean('identityService')) {
            identityService.installThreadIdentity(null)
        }
    }

    private static final Map<String, Class> FRAMEWORK_BEANS = [
        configService     : TestConfigService,
        userService       : TestUserService,
        identityService   : IdentityService,
        clusterService    : TestClusterService,
        trackService      : TestTrackService,
        emailService      : TestEmailService,
        xhExceptionHandler: ExceptionHandler
    ] as Map<String, Class>

    // Call the BeanBuilder explicitly - a bare `"$name"(clazz)` in the DSL closure would resolve to
    // this trait's own same-named getters (e.g. getIdentityService()) first.
    @CompileDynamic
    private void defineAutowiredBeans(Map<String, Class> beans) {
        defineBeans {
            def beanBuilder = delegate
            beans.each { String name, Class clazz ->
                beanBuilder."$name"(clazz) { bean -> bean.autowire = 'byName' }
            }
        }
    }
}
