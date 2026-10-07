/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.test

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import grails.testing.services.ServiceUnitTest
import io.xh.hoist.BaseService
import io.xh.hoist.cache.Cache
import io.xh.hoist.config.ConfigService
import io.xh.hoist.exception.NotAuthorizedException
import io.xh.hoist.log.LogSupportMarker
import io.xh.hoist.util.DateTimeUtils
import io.xh.hoist.util.Utils
import org.slf4j.LoggerFactory
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

import java.util.concurrent.atomic.AtomicInteger

import static io.xh.hoist.util.DateTimeUtils.HOURS

/** A typical app service - creates a Cache in a field initializer, reads config, logs, uses identity. */
class SampleService extends BaseService {

    static clearCachesConfigs = []

    ConfigService configService

    Cache<String, String> forecasts = createCache(name: 'forecasts', expireTime: 1 * HOURS)
    AtomicInteger timerRuns = new AtomicInteger()

    String greeting() {
        logInfo('greeting requested')
        "${configService.getString('greetingPrefix')} ${user?.displayName}".toString()
    }

    String cached(String key) {
        forecasts.getOrCreate(key) { "computed-$key".toString() }
    }

    void init() {
        createTimer(name: 'tick', runFn: { timerRuns.incrementAndGet() }, interval: 1 * HOURS, primaryOnly: true)
    }

    boolean isPrimaryInstance() {
        isPrimary
    }

    // Not final - exposed for spec assertions only.
    def timers() { resources.values().findAll { it instanceof io.xh.hoist.util.Timer } }
}

/** A second service, used as a collaborator of the service under test. */
class OtherService extends BaseService {
    ConfigService configService
}

class HoistUnitTestSpec extends Specification implements ServiceUnitTest<SampleService>, HoistUnitTest {

    def 'service with field-initializer cache can be created and used'() {
        given:
        def svc = service

        expect:
        svc.cached('a') == 'computed-a'
        svc.forecasts.get('a') == 'computed-a'
    }

    def 'service reads soft config and sees the logged-in user'() {
        given:
        testConfigService.set('greetingPrefix', 'Hello')
        def svc = service
        def user = new TestUser('alice', ['HOIST_ADMIN'])
        user.displayName = 'Alice A'

        when:
        loginAs(user)

        then:
        svc.greeting() == 'Hello Alice A'
        svc.username == 'alice'
        svc.authUsername == 'alice'
        svc.user.isHoistAdmin
        !identityService.impersonating
        Utils.identityService.is(identityService)
        Utils.configService.is(testConfigService)
    }

    def 'logging works at all levels, and includes the user'() {
        given:
        def svc = service
        testConfigService.set('greetingPrefix', 'Hi')
        def logger = LoggerFactory.getLogger(SampleService) as Logger
        def appender = new ListAppender<ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)
        def origLevel = logger.level
        logger.level = Level.DEBUG
        loginAs('alice')

        when:
        svc.greeting()
        svc.logWarn('careful')
        svc.logDebug('detail')
        svc.withInfo('timed block') { 1 + 1 }

        then: 'messages and the user are carried on the LogSupportMarker'
        def logged = appender.list.collectMany { ILoggingEvent e ->
            e.markerList.findAll { it instanceof LogSupportMarker }.collectMany { ((LogSupportMarker) it).messages }
        }*.toString()
        logged.any { it.contains('greeting requested') }
        logged.any { it.contains('careful') }
        logged.any { it.contains('timed block') }
        logged.any { it.contains('_user') && it.contains('alice') }

        cleanup:
        logger?.detachAppender(appender)
        logger.level = origLevel
    }

    def 'user is null when logged out'() {
        given:
        def svc = service

        expect:
        svc.user == null
        svc.username == null

        when:
        loginAs('bob')

        then:
        svc.username == 'bob'

        when:
        logout()

        then:
        svc.user == null
    }

    def 'loginAs a name creates a role-less TestUser, and reuses an existing user'() {
        when:
        def bob = loginAs('bob')

        then:
        bob instanceof TestUser
        bob.roles.isEmpty()
        testUserService.find('bob').is(bob)

        when:
        def admin = new TestUser('bob', ['HOIST_ADMIN'])
        testUserService.add(admin)

        then:
        loginAs('bob').is(admin)
    }

    def 'impersonation is reflected in user and authUser'() {
        given:
        def svc = service
        testUserService.add(new TestUser('admin', ['HOIST_ADMIN']), new TestUser('target'))

        when:
        impersonate('target', 'admin')

        then:
        svc.username == 'target'
        svc.authUsername == 'admin'
        svc.authUser.isHoistAdmin
        !svc.user.isHoistAdmin
        identityService.impersonating
    }

    def 'withUser restores the previous identity'() {
        given:
        def svc = service
        loginAs('alice')

        when:
        def inner = withUser(new TestUser('bob')) { svc.username }

        then:
        inner == 'bob'
        svc.username == 'alice'

        when:
        logout()
        withUser(new TestUser('bob')) { svc.username }

        then:
        svc.username == null
    }

    def 'withUser restores identity if the closure throws'() {
        given:
        def svc = service
        loginAs('alice')

        when:
        withUser(new TestUser('bob')) { throw new IllegalStateException('boom') }

        then:
        thrown(IllegalStateException)
        svc.username == 'alice'
    }

    def 'the service under test is injected with the Hoist beans by name'() {
        expect:
        service.identityService.is(identityService)
        service.clusterService.is(testClusterService)
        service.configService.is(testConfigService)
        Utils.configService.is(testConfigService)
        Utils.identityService.is(identityService)
    }

    def 'defineService registers an autowired service bean for collaborators'() {
        when:
        def other = defineService(OtherService)

        then:
        other.configService.is(testConfigService)
        applicationContext.getBean('otherService').is(other)

        when: 'defined again, e.g. by a later feature'
        def replacement = defineService(OtherService)

        then: 'the earlier instance is replaced and destroyed'
        !replacement.is(other)
        other.destroyed
    }
    def 'timers on a primary instance run when init() is called'() {
        given:
        def svc = service

        when:
        svc.init()
        svc.timers().each { it.forceRun() }

        then:
        svc.isPrimaryInstance()
        new PollingConditions(timeout: 5).eventually {
            assert svc.timerRuns.get() >= 1
        }
    }

    def 'primary status can be simulated'() {
        given:
        def svc = service

        expect:
        svc.isPrimaryInstance()

        when:
        testClusterService.primaryInstance = false

        then:
        !svc.isPrimaryInstance()
    }

    def 'app time zone can be set'() {
        when:
        useAppTimeZone('America/New_York')

        then:
        DateTimeUtils.appTimeZone.ID == 'America/New_York'

        when:
        useAppTimeZone('Asia/Tokyo')

        then:
        DateTimeUtils.appTimeZone.ID == 'Asia/Tokyo'
    }

    def 'HoistAssertions use the context exception handler'() {
        expect:
        HoistAssertions.httpStatusFor(new NotAuthorizedException()) == 403
    }
}
