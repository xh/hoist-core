/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.log

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.TraceFlags
import io.opentelemetry.api.trace.TraceState
import io.xh.hoist.log.LogLevelService
import io.xh.hoist.test.HoistSpec
import org.grails.spring.beans.factory.InstanceFactoryBean
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.Marker
import org.slf4j.helpers.BasicMarkerFactory
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import spock.lang.PendingFeature
import spock.lang.Subject

class LogSupportConverterSpec extends HoistSpec {

    // The Grails test context is shared by all features in a spec, so remove the per-feature stub.
    def cleanup() {
        if (applicationContext.containsBeanDefinition('logLevelService')) {
            ((BeanDefinitionRegistry) applicationContext).removeBeanDefinition('logLevelService')
        }
    }

    private void stubLogLevelService(LogLevelService stub) {
        defineBeans { logLevelService(InstanceFactoryBean, stub, LogLevelService) }
    }

    static final String TRACE_ID = '0af7651916cd43dd8448eb211c80319c'
    static final String SPAN_ID = 'b7ad6b7169203331'
    static final String INDENT = ' ' * 11

    @Subject
    LogSupportConverter converter = new LogSupportConverter()

    Logger logger = LoggerFactory.getLogger('io.xh.hoist.test.converter')

    def 'events without a LogSupportMarker return the formatted message unchanged'() {
        given:
        def event = Stub(ILoggingEvent) {
            getMarker() >> marker
            getFormattedMessage() >> 'plain message'
        }

        expect:
        converter.convert(event) == 'plain message'

        where:
        marker << [null, new BasicMarkerFactory().getMarker('other')]
    }

    def 'messages #messages render as [#expected]'() {
        expect:
        convert(messages) == expected

        where:
        messages                      | expected
        ['a', 'b']                    | 'a | b'
        ['a', ['b', 'c']]             | 'a | b | c'
        [[_status: 'completed']]      | 'completed'
        [[_elapsedMs: 300]]           | '300ms'
        [[foo: 1, bar: 'x']]          | 'foo=1 | bar=x'
        [[1: 'v']]                    | '1=v'
        ['a', [_elapsedMs: 5]]        | 'a | 5ms'
        [42, true]                    | '42 | true'
    }

    def 'a throwable map value is summarised via the exception handler'() {
        when:
        def ret = convert([[err: new RuntimeException('boom')]])

        then:
        ret.startsWith('err=boom')
    }

    def 'a trailing throwable is summarised and followed by an indented stack trace'() {
        when:
        def ret = convert(['failed', new IllegalStateException('bad state')])
        def lines = ret.readLines()

        then:
        lines[0].startsWith('failed | bad state')
        lines.size() > 2
        lines.tail().every { it.startsWith(INDENT) }
        lines.any { it.contains('LogSupportConverterSpec') }
    }

    def 'a throwable that is not last gets no stack trace'() {
        when:
        def ret = convert([new IllegalStateException('bad state'), 'after'])

        then:
        !ret.contains('\n')
        ret.endsWith(' | after')
    }

    def 'the stack trace is suppressed when the log level service says so'() {
        given:
        stubLogLevelService(Stub(LogLevelService) {
            shouldSuppressStackTrace('io.xh.hoist.test.converter') >> true
        })

        when:
        def ret = convert(['failed', new IllegalStateException('bad state')])

        then:
        !ret.contains('\n')
        ret.startsWith('failed | bad state')
    }

    def 'the stack trace is retained when the log level service throws'() {
        given:
        stubLogLevelService(Stub(LogLevelService) {
            shouldSuppressStackTrace(_) >> { throw new IllegalStateException('no service') }
        })

        expect:
        convert(['failed', new IllegalStateException('bad state')]).contains('\n')
    }

    def 'traceId is appended at level #level when present=#present'() {
        when:
        def ret = convert(['msg'], level, present)

        then:
        ret == (appended ? "msg | traceId=$TRACE_ID" : 'msg')

        where:
        level       | present | appended
        Level.ERROR | true    | true
        Level.WARN  | true    | false
        Level.INFO  | true    | false
        Level.ERROR | false   | false
        Level.WARN  | false   | false
    }

    def 'a subclass can override the delimiter'() {
        given:
        def custom = new LogSupportConverter() {
            @Override
            protected String getDelimiter() { ', ' }
        }

        when:
        def marker = new LogSupportMarker(logger, ['a', [x: 1, y: 2]] as Object[])
        def event = Stub(ILoggingEvent) {
            getMarker() >> marker
            getLevel() >> Level.INFO
        }

        then:
        custom.convert(event) == 'a, x=1, y=2'
    }

    def 'null message elements render as the string null'() {
        expect:
        convert(['a', null]) == 'a | null'
    }

    def 'null map values render as the string null'() {
        expect:
        convert([[k: null]]) == 'k=null'
    }

    @PendingFeature(reason = 'messages.last() throws NoSuchElementException for a marker with no messages, e.g. logInfo() with no args')
    def 'an empty message list renders as an empty string'() {
        expect:
        convert([]) == ''
    }

    private String convert(List messages, Level level = Level.INFO, boolean withTrace = false) {
        Marker marker
        if (withTrace) {
            def scope = Span.wrap(SpanContext.create(TRACE_ID, SPAN_ID, TraceFlags.getSampled(), TraceState.getDefault())).makeCurrent()
            try {
                marker = new LogSupportMarker(logger, messages as Object[])
            } finally {
                scope.close()
            }
        } else {
            marker = new LogSupportMarker(logger, messages as Object[])
        }
        def event = Stub(ILoggingEvent) {
            getMarker() >> marker
            getLevel() >> level
        }
        converter.convert(event)
    }
}
