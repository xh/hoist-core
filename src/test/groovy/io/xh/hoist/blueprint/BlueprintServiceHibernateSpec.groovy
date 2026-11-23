/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.blueprint

import grails.test.hibernate.HibernateSpec
import grails.testing.services.ServiceUnitTest
import io.xh.hoist.test.HoistUnitTest
import io.xh.hoist.test.fakes.TestUser
import spock.util.concurrent.PollingConditions

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

import static io.xh.hoist.json.JSONParser.parseObject

/**
 * Proves, on a real database, what {@link BlueprintService} relies on the database for: the row
 * lock behind its version check, the unique indexes behind publish numbers and personalizations,
 * and the foreign keys behind its cascade. Each operation commits in its own transaction, as in a
 * running app.
 *
 * Runs on H2. `SELECT ... FOR UPDATE` and unique indexes behave the same in principle on MySQL
 * (InnoDB), SQL Server and Postgres, but this spec proves the logic on H2 only.
 */
class BlueprintServiceHibernateSpec extends HibernateSpec implements ServiceUnitTest<BlueprintService>, HoistUnitTest {

    static final String T = 'test.app'

    static final Map BP = [schema: [blueprint: 1]]

    TestUser author = new TestUser('owner', ['DESIGNER']),
             reader = new TestUser('reader')

    List<Class> getDomainClasses() { [Blueprint, BlueprintPublish, BlueprintPersonalization] }

    Map getConfiguration() {
        super.configuration + [
            'dataSource.url': 'jdbc:h2:mem:blueprintSpec;LOCK_TIMEOUT=10000;DB_CLOSE_DELAY=-1',
            // Hoist's second-level cache is Hazelcast-backed, and there is no cluster here.
            'hibernate.cache.use_second_level_cache': false,
            'hibernate.cache.use_query_cache': false
        ]
    }

    // Keep what each feature commits until cleanup(), which removes it.
    boolean isRollback() { false }

    def setup() {
        testConfigService.registerTypedConfig('xhBlueprintConfig', BlueprintConfig)
        testConfigService.set('xhBlueprintConfig', [enabled: true, createRoles: ['DESIGNER'], globalShareRoles: ['DESIGNER']])
        loginAs(author)
    }

    def cleanup() {
        Blueprint.withNewTransaction {
            BlueprintPersonalization.where {}.deleteAll()
            BlueprintPublish.where {}.deleteAll()
            Blueprint.where {}.deleteAll()
        }
    }

    def 'a stale base is refused and changes nothing'() {
        given:
        String token = committed { service.create(T, 'Risk', null, BP) }.token
        committed { service.saveDraft(T, token, [*: BP, title: 'v2'], 1, []) }

        when:
        committed { service.saveDraft(T, token, [*: BP, title: 'stale'], 1, []) }

        then:
        thrown(BlueprintStaleBaseException)
        Blueprint.withNewTransaction { parseObject(Blueprint.findByToken(token).draft).title } == 'v2'

        when:
        committed { service.publish(T, token, 1) }

        then:
        thrown(BlueprintStaleBaseException)
        Blueprint.withNewTransaction { BlueprintPublish.count() } == 0
    }

    def 'unique indexes refuse a second publish of one number, and a second personalization of one preset'() {
        given:
        String token = committed { service.create(T, 'Risk', null, BP) }.token
        committed { service.publish(T, token, 1) }

        when:
        Blueprint.withNewTransaction {
            new BlueprintPublish(blueprint: Blueprint.findByToken(token), number: 1, content: '{}').save(flush: true, validate: false)
        }

        then:
        def e = thrown(Exception)
        isConstraintViolation(e)

        when:
        committed { service.savePersonalization(T, token, 'view:a', [], 1) }
        Blueprint.withNewTransaction {
            new BlueprintPersonalization(blueprint: Blueprint.findByToken(token), username: 'owner', preset: 'view:a').save(flush: true, validate: false)
        }

        then:
        def e2 = thrown(Exception)
        isConstraintViolation(e2)
    }

    def 'a publish takes a new note, label or pin, but its content never changes'() {
        given:
        String token = Blueprint.withNewTransaction { service.create(T, 'Risk', null, BP).token }
        Blueprint.withNewTransaction { service.publish(T, token, 1, 'First') }

        when:
        Blueprint.withNewTransaction { service.updatePublish(T, token, 1, [label: 'Q3', pinned: true]) }

        then:
        def saved = Blueprint.withNewTransaction { service.getPublish(T, token, 1) }
        [saved.note, saved.label, saved.pinned] == ['First', 'Q3', true]

        when:
        Blueprint.withNewTransaction {
            def publish = BlueprintPublish.findByNumber(1)
            publish.content = '{}'
            publish.save(flush: true)
        }

        then:
        def e = thrown(Exception)
        rootCause(e) instanceof IllegalStateException
    }

    def 'a save waits on the row lock of a concurrent save, then finds its base stale'() {
        given:
        String token = committed { service.create(T, 'Risk', null, BP) }.token
        def locked = new CountDownLatch(1),
            release = new CountDownLatch(1)
        Throwable resultB = null

        // Thread A takes the row lock, as saveDraft does, and holds it until released.
        Throwable resultA = null
        def threadA = Thread.start {
            try {
                Blueprint.withNewTransaction {
                    Blueprint bp = Blueprint.findByToken(token, [lock: true])
                    locked.countDown()
                    release.await(10, TimeUnit.SECONDS)
                    bp.draft = '{"title":"A"}'
                    bp.draftVersion = 2
                    bp.save(flush: true)
                }
            } catch (Throwable t) {
                resultA = t
            }
        }
        boolean gotLock = locked.await(10, TimeUnit.SECONDS)

        // Thread B saves on the same base, and must block until A commits.
        def threadB = Thread.start {
            try {
                loginAs(author)
                service.saveDraft(T, token, [*: BP, title: 'B'], 1, [])
            } catch (Throwable t) {
                resultB = t
            }
        }

        when:
        new PollingConditions(timeout: 10).eventually {
            assert threadB.stackTrace.any { it.className.startsWith('org.h2') }
        }
        release.countDown()
        threadA.join(10_000)
        threadB.join(10_000)

        then:
        gotLock
        resultA == null
        resultB instanceof BlueprintStaleBaseException
        Blueprint.withNewTransaction {
            Blueprint bp = Blueprint.findByToken(token)
            [bp.draftVersion, parseObject(bp.draft).title]
        } == [2L, 'A']
    }

    def 'deleting a blueprint removes its publishes and every user\'s personalizations, which its foreign keys protect'() {
        given:
        String token = committed { service.create(T, 'Risk', null, BP, [], ['*']) }.token
        committed { service.publish(T, token, 1) }
        [author, reader].each { user ->
            loginAs(user)
            committed { service.savePersonalization(T, token, null, [], 1) }
        }
        loginAs(author)

        when: 'the blueprint row is deleted alone'
        Blueprint.withNewTransaction { Blueprint.findByToken(token).delete(flush: true) }

        then:
        def e = thrown(Exception)
        isConstraintViolation(e)

        when:
        committed { service.delete(T, token) }

        then:
        Blueprint.withNewTransaction {
            [Blueprint.count(), BlueprintPublish.count(), BlueprintPersonalization.count()]
        } == [0, 0, 0]
    }

    //------------------
    // Implementation
    //------------------
    // HibernateSpec wraps each feature in a transaction, which the service would join. Commit each
    // call on its own instead, as in a running app.
    private <T> T committed(Closure<T> call) {
        Blueprint.withNewTransaction { call() }
    }

    private static boolean isConstraintViolation(Throwable e) {
        for (Throwable t = e; t; t = t.cause) {
            if (t.class.simpleName.contains('ConstraintViolation') || t.message?.toLowerCase()?.contains('constraint')) return true
        }
        return false
    }

    private static Throwable rootCause(Throwable e) {
        Throwable ret = e
        while (ret.cause && ret.cause != ret) ret = ret.cause
        return ret
    }
}
