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
import grails.util.Holders
import io.xh.hoist.BaseService
import io.xh.hoist.cache.Cache
import io.xh.hoist.config.ConfigService
import io.xh.hoist.exception.NotAuthorizedException
import io.xh.hoist.log.LogSupportMarker
import io.xh.hoist.util.DateTimeUtils
import io.xh.hoist.util.Utils
import org.slf4j.LoggerFactory
import spock.util.concurrent.PollingConditions

import java.util.concurrent.atomic.AtomicInteger

import static io.xh.hoist.util.DateTimeUtils.HOURS

/** A typical app service - creates a Cache in a field initializer, reads config, logs, uses identity. */
class SampleService extends BaseService {

    static clearCachesConfigs = []

    ConfigService configService

    // Not a registered bean, so supplied as a prop.
    SampleCollaborator collaborator

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

class SampleCollaborator {
    String value = 'real'
}

class HoistTestContextSpec extends HoistSpec {

    def 'service with field-initializer cache can be created and used'() {
        given:
        def svc = createService(SampleService)

        expect:
        svc.cached('a') == 'computed-a'
        svc.forecasts.get('a') == 'computed-a'
    }

    def 'service reads soft config and sees the logged-in user'() {
        given:
        hoist.configService.set('greetingPrefix', 'Hello')
        def svc = createService(SampleService)
        def user = new TestUser('alice', ['HOIST_ADMIN'])
        user.displayName = 'Alice A'

        when:
        loginAs(user)

        then:
        svc.greeting() == 'Hello Alice A'
        svc.username == 'alice'
        svc.authUsername == 'alice'
        svc.user.isHoistAdmin
        !hoist.identityService.impersonating
        Utils.identityService.is(hoist.identityService)
        Utils.configService.is(hoist.configService)
    }

    def 'logging works at all levels, and includes the user'() {
        given:
        def svc = createService(SampleService)
        hoist.configService.set('greetingPrefix', 'Hi')
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
        def svc = createService(SampleService)

        expect:
        svc.user == null
        svc.username == null

        when:
        loginAs('bob')

        then:
        svc.username == 'bob'

        when:
        hoist.logout()

        then:
        svc.user == null
    }

    def 'loginAs a name creates a role-less TestUser, and reuses an existing user'() {
        when:
        def bob = loginAs('bob')

        then:
        bob instanceof TestUser
        bob.roles.isEmpty()
        hoist.userService.find('bob').is(bob)

        when:
        def admin = new TestUser('bob', ['HOIST_ADMIN'])
        hoist.userService.add(admin)

        then:
        loginAs('bob').is(admin)
    }

    def 'impersonation is reflected in user and authUser'() {
        given:
        def svc = createService(SampleService)
        hoist.userService.add(new TestUser('admin', ['HOIST_ADMIN']), new TestUser('target'))

        when:
        hoist.impersonate('target', 'admin')

        then:
        svc.username == 'target'
        svc.authUsername == 'admin'
        svc.authUser.isHoistAdmin
        !svc.user.isHoistAdmin
        hoist.identityService.impersonating
    }

    def 'withUser restores the previous identity'() {
        given:
        def svc = createService(SampleService)
        loginAs('alice')

        when:
        def inner = hoist.withUser(new TestUser('bob')) { svc.username }

        then:
        inner == 'bob'
        svc.username == 'alice'

        when:
        hoist.logout()
        hoist.withUser(new TestUser('bob')) { svc.username }

        then:
        svc.username == null
    }

    def 'withUser restores identity if the closure throws'() {
        given:
        def svc = createService(SampleService)
        loginAs('alice')

        when:
        hoist.withUser(new TestUser('bob')) { throw new IllegalStateException('boom') }

        then:
        thrown(IllegalStateException)
        svc.username == 'alice'
    }

    def 'props supplied to createService override injected beans'() {
        given:
        def collaborator = new SampleCollaborator(value: 'mock')
        def svc = createService(SampleService, [collaborator: collaborator, configService: Stub(ConfigService)])

        expect:
        svc.collaborator.is(collaborator)
        !svc.configService.is(hoist.configService)
    }

    def 'created services are injected by name, and registered as beans for later services'() {
        when:
        def svc = createService(SampleService)

        then:
        svc.identityService.is(hoist.identityService)
        svc.clusterService.is(hoist.clusterService)
        svc.configService.is(hoist.configService)
        hoist.getBean('sampleService').is(svc)
        hoist.getBean('nonexistent') == null
    }

    def 'autowire leaves non-null properties and incompatible types untouched'() {
        given:
        def stubConfig = Stub(ConfigService)
        def target = new SampleService(configService: stubConfig)

        when:
        hoist.registerBean('collaborator', 'not a collaborator')
        hoist.autowire(target)

        then:
        target.configService.is(stubConfig)
        target.collaborator == null
        target.identityService.is(hoist.identityService)
    }

    def 'registerBean replaces an existing bean'() {
        given:
        def replacement = new TestConfigService()

        when:
        hoist.registerBean('configService', replacement)

        then:
        Utils.configService.is(replacement)
    }

    def 'timers on a primary instance run when init() is called'() {
        given:
        def svc = createService(SampleService)

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
        def svc = createService(SampleService)

        expect:
        svc.isPrimaryInstance()

        when:
        hoist.clusterService.primaryInstance = false

        then:
        !svc.isPrimaryInstance()
    }

    def 'app time zone can be set'() {
        when:
        hoist.setAppTimeZone('America/New_York')

        then:
        DateTimeUtils.appTimeZone.ID == 'America/New_York'

        when:
        hoist.setAppTimeZone('Asia/Tokyo')

        then:
        DateTimeUtils.appTimeZone.ID == 'Asia/Tokyo'
    }

    def 'services are destroyed when the context closes'() {
        given:
        def ctx = hoist
        def svc = createService(SampleService)
        svc.init()
        // Let the timer's first run complete, so it can't race context teardown on its own thread.
        new PollingConditions(timeout: 5).eventually {
            assert svc.timerRuns.get() >= 1
        }

        when:
        ctx.close()

        then:
        svc.destroyed
        HoistTestContext.current == null

        when: 'closed again'
        ctx.close()

        then:
        noExceptionThrown()
    }

    def 'install re-registers with Holders after Holders.clear()'() {
        when: 'Grails testing support cleanup clears all discovery strategies'
        Holders.clear()
        def ctx = HoistTestContext.install()

        then:
        Utils.configService.is(ctx.configService)
    }

    def 'install replaces and closes any previous context'() {
        given:
        def first = hoist

        when:
        def second = HoistTestContext.install()

        then:
        !second.is(first)
        HoistTestContext.current.is(second)
        Utils.configService.is(second.configService)
    }

    def 'HoistAssertions use the context exception handler'() {
        expect:
        HoistAssertions.httpStatusFor(new NotAuthorizedException()) == 403
    }
}
