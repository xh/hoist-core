/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.log

import ch.qos.logback.classic.Level
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
}
