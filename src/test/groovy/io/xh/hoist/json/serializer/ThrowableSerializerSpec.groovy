/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.json.serializer

import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.TraceFlags
import io.opentelemetry.api.trace.TraceState
import io.xh.hoist.exception.ExternalHttpException
import io.xh.hoist.exception.NotAuthorizedException
import io.xh.hoist.exception.RoutineRuntimeException
import io.xh.hoist.json.JSONFormat
import io.xh.hoist.json.JSONParser
import io.xh.hoist.json.JSONSerializer
import spock.lang.Specification

class ThrowableSerializerSpec extends Specification {

    static class CustomFormatException extends RuntimeException implements JSONFormat {
        Object formatForJSON() { [custom: true] }
    }

    private Map render(Throwable t) {
        JSONParser.parseObject(JSONSerializer.serialize(t))
    }

    def 'routine exception includes name, message and isRoutine'() {
        expect:
        render(new RoutineRuntimeException('boom')) ==
            [name: 'RoutineRuntimeException', message: 'boom', isRoutine: true]
    }

    def 'non-routine exception omits isRoutine and includes cause message'() {
        expect:
        render(new RuntimeException('outer', new IOException('inner'))) ==
            [name: 'RuntimeException', message: 'outer', cause: 'inner']
    }

    def 'null message is omitted'() {
        expect:
        render(new RuntimeException()) == [name: 'RuntimeException']
    }

    def 'JSONFormat throwables use formatForJSON'() {
        expect:
        render(new CustomFormatException()) == [custom: true]
    }

    def 'routine flag for http exceptions'() {
        expect:
        render(new NotAuthorizedException()).isRoutine == true
        !render(new ExternalHttpException('x', null, 502)).containsKey('isRoutine')
    }

    def 'traceId is included only with an active valid span'() {
        given:
        def traceId = '0af7651916cd43dd8448eb211c80319c'
        def ctx = SpanContext.create(traceId, 'b7ad6b7169203331', TraceFlags.getSampled(), TraceState.getDefault())

        expect:
        !render(new RuntimeException('x')).containsKey('traceId')

        when:
        def scope = Span.wrap(ctx).makeCurrent()
        def json
        try {
            json = render(new RuntimeException('x'))
        } finally {
            scope.close()
        }

        then:
        json.traceId == traceId
        !render(new RuntimeException('x')).containsKey('traceId')
    }
}
