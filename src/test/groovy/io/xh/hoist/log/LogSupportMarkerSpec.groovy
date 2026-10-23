/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.log

import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.TraceFlags
import io.opentelemetry.api.trace.TraceState
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import spock.lang.Specification

class LogSupportMarkerSpec extends Specification {

    static final String TRACE_ID = '0af7651916cd43dd8448eb211c80319c'
    static final String SPAN_ID = 'b7ad6b7169203331'

    Logger logger = LoggerFactory.getLogger('io.xh.hoist.test.marker')

    def 'nested message lists are flattened'() {
        expect:
        new LogSupportMarker(logger, ['a', ['b', ['c']]]).messages == ['a', 'b', 'c']
    }

    def 'a single object becomes a one element list'() {
        expect:
        new LogSupportMarker(logger, 'a').messages == ['a']
    }

    def 'maps are kept intact as elements'() {
        given:
        def map = [k: 'v']

        expect:
        new LogSupportMarker(logger, ['a', map]).messages == ['a', map]
    }

    def 'retains the sending logger'() {
        expect:
        new LogSupportMarker(logger, 'a').logger.is(logger)
    }

    def 'traceId is null with no current span'() {
        expect:
        new LogSupportMarker(logger, 'a').traceId == null
    }

    def 'traceId is null when the current span is invalid'() {
        when:
        def scope = Span.getInvalid().makeCurrent()
        def marker
        try {
            marker = new LogSupportMarker(logger, 'a')
        } finally {
            scope.close()
        }

        then:
        marker.traceId == null
    }

    def 'traceId is captured from a valid current span'() {
        given:
        def span = Span.wrap(SpanContext.create(TRACE_ID, SPAN_ID, TraceFlags.getSampled(), TraceState.getDefault()))

        when:
        def scope = span.makeCurrent()
        def marker
        try {
            marker = new LogSupportMarker(logger, 'a')
        } finally {
            scope.close()
        }

        then:
        marker.traceId == TRACE_ID
    }

    def 'implements the Marker contract degenerately'() {
        given:
        def marker = new LogSupportMarker(logger, 'a')

        expect:
        marker.name == 'LogSupportMarker'
        !marker.hasChildren()
        !marker.hasReferences()
        !marker.contains('LogSupportMarker')
        !marker.contains(marker)
        !marker.remove(marker)
        marker.iterator() == null
    }
}
