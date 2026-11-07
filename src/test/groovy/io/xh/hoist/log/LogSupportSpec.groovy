/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.log

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.xh.hoist.test.HoistSpec
import org.slf4j.LoggerFactory

class LogSupportSpec extends HoistSpec {

    static class Logging implements LogSupport {}

    def setup() {
        (LoggerFactory.getLogger(Logging) as ch.qos.logback.classic.Logger).level = Level.INFO
    }

    def 'logging with no messages does not throw when a user is logged in'() {
        given:
        loginAs('bob')

        when:
        new Logging().logInfo()

        then:
        noExceptionThrown()
    }

    def 'apiDeprecated logs each distinct warning once, with removal version and guidance'() {
        given:
        def appender = attachListAppender()

        when:
        3.times { new Logging().apiDeprecated('LogSupportSpec.foo', v: 'v99', msg: 'Use bar instead') }
        new Logging().apiDeprecated('LogSupportSpec.baz')

        then:
        appender.list*.level == [Level.WARN, Level.WARN]
        appender.list.collect { (it.marker as LogSupportMarker).messages } == [
            ["'LogSupportSpec.foo' is deprecated and will be removed in v99", 'Use bar instead'],
            ["'LogSupportSpec.baz' is deprecated and will be removed in a future release"]
        ]

        cleanup:
        detachListAppender(appender)
    }

    //-------------------
    // Helpers
    //-------------------
    private ListAppender<ILoggingEvent> attachListAppender() {
        def appender = new ListAppender<ILoggingEvent>()
        appender.start()
        (LoggerFactory.getLogger(Logging) as ch.qos.logback.classic.Logger).addAppender(appender)
        appender
    }

    private void detachListAppender(ListAppender<ILoggingEvent> appender) {
        (LoggerFactory.getLogger(Logging) as ch.qos.logback.classic.Logger).detachAppender(appender)
    }
}
