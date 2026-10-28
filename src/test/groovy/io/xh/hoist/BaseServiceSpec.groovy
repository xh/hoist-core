/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist

import io.xh.hoist.test.HoistSpec
import org.grails.events.bus.SynchronousEventBus

/** Covers the resource and event helpers that {@link BaseService} provides to subclasses. */
class BaseServiceSpec extends HoistSpec {

    static class OnTimerService extends BaseService {
        int runs = 0
        void onTimer() { runs++ }
    }

    static class PrivateOnTimerService extends BaseService {
        int runs = 0
        private void onTimer() { runs++ }
    }

    static class NoOnTimerService extends BaseService {}

    static class SubscribingService extends BaseService {
        List received = []
        void init() {
            subscribe('xhTestEvent') { a, b -> received << [a, b] }
            subscribe('xhFailingEvent') { throw new IllegalStateException('boom') }
        }
    }

    Closure doWithSpring() {
        { ->
            // Grails 8 registers its event bus under this name only - there is no `eventBus` alias.
            grailsEventBus(SynchronousEventBus)
        }
    }

    //-------------------
    // createTimer
    //-------------------
    def 'createTimer without a runFn calls the subclass onTimer()'() {
        given:
        def svc = defineService(OnTimerService)
        def timer = svc.createTimer(name: 'test', interval: null)

        when:
        timer.runFn.call()

        then:
        svc.runs == 1
    }

    def 'createTimer without a runFn reaches a private onTimer()'() {
        given:
        def svc = defineService(PrivateOnTimerService)
        def timer = svc.createTimer(name: 'test', interval: null)

        when:
        timer.runFn.call()

        then:
        svc.runs == 1
    }

    def 'createTimer without a runFn or onTimer() is rejected'() {
        given:
        def svc = defineService(NoOnTimerService)

        when:
        svc.createTimer(name: 'test', interval: null)

        then:
        thrown(IllegalArgumentException)
    }

    //-------------------
    // subscribe
    //-------------------
    def 'subscribe receives events from the Grails event bus with their arguments'() {
        given:
        def svc = defineService(SubscribingService)
        svc.init()
        def bus = applicationContext.getBean('grailsEventBus', SynchronousEventBus)

        when:
        bus.notify('xhTestEvent', 1, 'two')

        then:
        svc.received == [[1, 'two']]
    }

    def 'subscribe logs handler exceptions and keeps later handlers running'() {
        given:
        def svc = defineService(SubscribingService)
        svc.init()
        def bus = applicationContext.getBean('grailsEventBus', SynchronousEventBus)
        def laterHandlerRan = false
        bus.on('xhFailingEvent') { laterHandlerRan = true }

        when:
        bus.notify('xhFailingEvent')

        then:
        noExceptionThrown()
        laterHandlerRan
    }

    def 'subscribe skips handlers once the service is destroyed'() {
        given:
        def svc = defineService(SubscribingService)
        svc.init()
        def bus = applicationContext.getBean('grailsEventBus', SynchronousEventBus)
        svc.destroy()

        when:
        bus.notify('xhTestEvent', 1, 2)

        then:
        svc.received.empty
    }
}
