/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.util

import io.xh.hoist.BaseService
import io.xh.hoist.test.HoistSpec
import spock.lang.Subject

import static io.xh.hoist.util.DateTimeUtils.HOURS

class RateMonitorSpec extends HoistSpec {

    // Stubbed owner, so no real timer runs - period rollover is driven by calling onTimer() directly.
    @Subject
    RateMonitor monitor

    def setup() {
        monitor = new RateMonitor('testRate', 5, 24 * HOURS, Stub(BaseService))
    }

    def 'limit is exceeded only when requests are strictly greater than the max'() {
        when:
        5.times { monitor.noteRequest() }

        then:
        monitor.periodRequests == 5
        !monitor.limitExceeded

        when:
        monitor.noteRequest()

        then:
        monitor.limitExceeded
    }

    def 'noteRequests adds the given count'() {
        when:
        monitor.noteRequests(3)
        monitor.noteRequests(0)

        then:
        monitor.periodRequests == 3
    }

    def 'compliant periods accumulate and reset the request count'() {
        when:
        3.times {
            monitor.noteRequests(2)
            monitor.onTimer()
        }

        then:
        monitor.periodsInCompliance == 3
        monitor.periodRequests == 0
    }

    def 'an exceeded period resets compliance to zero'() {
        given:
        2.times { monitor.onTimer() }

        when:
        monitor.noteRequests(6)

        then:
        monitor.periodsInCompliance == 0

        when:
        monitor.onTimer()

        then:
        monitor.periodsInCompliance == 0
        monitor.periodRequests == 0

        when:
        monitor.onTimer()

        then:
        monitor.periodsInCompliance == 1
    }

    def 'lowering the max below the current count resets compliance'() {
        given:
        2.times { monitor.onTimer() }
        monitor.noteRequests(3)

        when:
        monitor.maxPeriodRequests = 10

        then:
        monitor.periodsInCompliance == 2

        when:
        monitor.maxPeriodRequests = 2

        then:
        monitor.limitExceeded
        monitor.periodsInCompliance == 0
    }

    def 'adminStats reports config and state'() {
        given:
        monitor.noteRequests(2)

        expect:
        monitor.adminStats == [
            config             : [periodLength: 24 * HOURS, maxPeriodRequests: 5],
            periodRequests     : 2,
            limitExceeded      : false,
            periodsInCompliance: 0
        ]
        monitor.comparableAdminStats == []
    }

    def 'constructor registers a timer on the owner'() {
        given:
        def owner = Mock(BaseService)

        when:
        new RateMonitor('testRate', 5, 1000, owner)

        then:
        1 * owner.createTimer({ it.name == 'testRate' && it.interval == 1000 && it.runFn != null })
    }
}
