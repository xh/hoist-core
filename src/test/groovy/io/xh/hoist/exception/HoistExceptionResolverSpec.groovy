/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.exception

import io.xh.hoist.json.JSONParser
import io.xh.hoist.telemetry.trace.SpanRef
import io.xh.hoist.test.HoistSpec
import jakarta.servlet.ServletException
import org.codehaus.groovy.runtime.InvokerInvocationException
import org.springframework.core.Ordered
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import spock.lang.Subject

import java.lang.reflect.InvocationTargetException

import static io.xh.hoist.HoistFilter.REQUEST_SPAN_ATTR

class HoistExceptionResolverSpec extends HoistSpec {

    @Subject
    def resolver = new HoistExceptionResolver()

    def request = new MockHttpServletRequest(),
        response = new MockHttpServletResponse()

    def 'renders an Error thrown by a reflectively invoked action as Hoist JSON'() {
        given:
        def wrapped = new InvocationTargetException(new AssertionError('assert failed'))

        when:
        def mv = resolver.resolveException(request, response, null, wrapped)

        then:
        mv != null
        mv.empty
        response.status == 500
        response.contentType == 'application/json'
        renderedJSON() == [name: 'AssertionError', message: 'assert failed']
    }

    def 'renders an exception with its Hoist status and routine flag'() {
        when:
        resolver.resolveException(request, response, null, new RoutineRuntimeException('bad input'))

        then:
        response.status == 400
        renderedJSON() == [name: 'RoutineRuntimeException', message: 'bad input', isRoutine: true]
    }

    def 'unwraps #ex.class.simpleName to #expected.class.simpleName'() {
        expect:
        resolver.unwrap(ex).is(expected)

        where:
        ex                                                               | expected
        new InvocationTargetException(ERROR)                             | ERROR
        new ServletException('x', ERROR)                                 | ERROR
        new InvokerInvocationException(ROUTINE)                          | ROUTINE
        new InvokerInvocationException(new InvocationTargetException(ERROR)) | ERROR
        new InvocationTargetException(null)                              | ex
        new ServletException('x', ROUTINE)                               | ex
        new ServletException('x')                                        | ex
        ROUTINE                                                          | ROUTINE
    }

    def 'records the unwrapped exception on the request span'() {
        given:
        def span = Mock(SpanRef)
        request.setAttribute(REQUEST_SPAN_ATTR, span)
        def error = new StackOverflowError()

        when:
        resolver.resolveException(request, response, null, new ServletException('x', error))

        then:
        1 * span.recordException(error)
    }

    def 'runs after the Spring MVC resolvers, matching the Grails default'() {
        expect:
        resolver.order == Ordered.LOWEST_PRECEDENCE
    }

    //------------------------
    // Implementation
    //------------------------
    private static final Error ERROR = new LinkageError('linkage')
    private static final Exception ROUTINE = new RoutineRuntimeException('routine')

    private Map renderedJSON() {
        JSONParser.parseObject(response.contentAsString)
    }
}
