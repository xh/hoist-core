/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.log

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import grails.testing.gorm.DataTest
import grails.testing.services.ServiceUnitTest
import io.xh.hoist.test.HoistUnitTest
import org.slf4j.LoggerFactory
import spock.lang.Specification

/**
 * Tests {@link LogLevelService} against an in-memory {@link LogLevel} table and the live Logback
 * context. Loggers under the `io.xh.lls` prefix are reserved for these tests and reset after each.
 */
class LogLevelServiceSpec extends Specification implements ServiceUnitTest<LogLevelService>, DataTest, HoistUnitTest {

    static final String A = 'io.xh.lls.a', A_SUB = 'io.xh.lls.a.sub', B = 'io.xh.lls.b'

    Class[] getDomainClassesToMock() { [LogLevel] }

    def cleanup() {
        [A, A_SUB, B].each { logger(it).level = null }
    }

    //-------------------
    // Level adjustments
    //-------------------
    def 'calculateAdjustments applies configured levels to loggers and remembers their defaults'() {
        given:
        logger(A).level = Level.INFO
        logLevel(A, 'Debug')
        logLevel(B, 'Error')

        when:
        service.calculateAdjustments()

        then:
        logger(A).level == Level.DEBUG
        logger(B).level == Level.ERROR
        service.getDefaultLevel(A) == 'Info'
        service.getDefaultLevel(B) == 'Inherit'
        service.getEffectiveLevel(A) == 'Debug'
    }

    def 'removing an override restores the logger to its default on the next calculation'() {
        given:
        logger(A).level = Level.INFO
        def override = logLevel(A, 'Trace')
        service.calculateAdjustments()

        when:
        override.delete(flush: true)
        service.calculateAdjustments()

        then:
        logger(A).level == Level.INFO
        service.getDefaultLevel(A) == 'Info'
    }

    def 'an Inherit override clears the logger level'() {
        given:
        logger(A).level = Level.WARN
        logLevel(A, 'Inherit')

        when:
        service.calculateAdjustments()

        then:
        logger(A).level == null
        service.getEffectiveLevel(A) == service.getEffectiveLevel('io.xh.lls')
    }

    def 'a LogLevel row with no level is not an adjustment'() {
        given:
        logger(A).level = Level.INFO
        new LogLevel(name: A, suppressStackTrace: true).save(flush: true, failOnError: true)

        when:
        service.calculateAdjustments()

        then:
        logger(A).level == Level.INFO
    }

    def 'default and effective levels are reported in title case, with Inherit for an unset logger'() {
        given:
        logger(A).level = Level.WARN

        expect:
        service.getDefaultLevel(A) == 'Warn'
        service.getEffectiveLevel(A) == 'Warn'
        service.getDefaultLevel(A_SUB) == 'Inherit'
        service.getEffectiveLevel(A_SUB) == 'Warn'
    }

    def 'root is accepted as an alias for the root logger'() {
        expect:
        service.getEffectiveLevel('root') == service.getEffectiveLevel(Logger.ROOT_LOGGER_NAME)
    }

    //-------------------
    // Flag overrides
    //-------------------
    def 'flag overrides match by prefix, most specific first, and default to false'() {
        given: 'a broad row for the package and a narrower row for logger A that flips both flags'
        new LogLevel(name: 'io.xh.lls', suppressStackTrace: true, includeStartMessages: false).save(flush: true, failOnError: true)
        new LogLevel(name: A, suppressStackTrace: false, includeStartMessages: true).save(flush: true, failOnError: true)

        when:
        service.calculateAdjustments()

        then: 'A and its children take the narrower row'
        !service.shouldSuppressStackTrace(A)
        !service.shouldSuppressStackTrace(A_SUB)
        service.shouldIncludeStartMessages(A)
        service.shouldIncludeStartMessages(A_SUB)

        and: 'B falls through to the package row'
        service.shouldSuppressStackTrace(B)
        !service.shouldIncludeStartMessages(B)

        and: 'an unrelated logger matches nothing'
        !service.shouldSuppressStackTrace('other')
        !service.shouldIncludeStartMessages('other')
    }

    def 'rows without a flag do not participate in that flag\'s overrides'() {
        given:
        new LogLevel(name: A, level: 'Debug').save(flush: true, failOnError: true)
        new LogLevel(name: B, suppressStackTrace: true).save(flush: true, failOnError: true)

        when:
        service.calculateAdjustments()

        then:
        !service.shouldSuppressStackTrace(A)
        service.shouldSuppressStackTrace(B)
        !service.shouldIncludeStartMessages(B)
    }

    def 'clearCaches recalculates'() {
        given:
        logLevel(A, 'Debug')

        when:
        service.clearCaches()

        then:
        logger(A).level == Level.DEBUG
    }

    //-------------------
    // Helpers
    //-------------------
    private static Logger logger(String name) {
        LoggerFactory.getLogger(name) as Logger
    }

    private static LogLevel logLevel(String name, String level) {
        new LogLevel(name: name, level: level).save(flush: true, failOnError: true)
    }
}
