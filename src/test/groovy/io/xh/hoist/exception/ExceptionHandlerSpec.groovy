/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.exception

import io.xh.hoist.json.JSONSerializer
import io.xh.hoist.log.LogSupport
import io.xh.hoist.telemetry.trace.SpanRef
import jakarta.servlet.ServletException
import jakarta.servlet.http.HttpServletResponse
import org.codehaus.groovy.runtime.InvokerInvocationException
import org.grails.web.util.GrailsApplicationAttributes
import org.grails.web.servlet.mvc.GrailsWebRequest
import org.grails.web.util.WebUtils
import org.slf4j.Logger
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockServletContext
import org.springframework.validation.BeanPropertyBindingResult
import org.springframework.web.context.request.RequestContextHolder
import spock.lang.Specification
import spock.lang.Subject

import java.lang.reflect.InvocationTargetException
import java.util.concurrent.ExecutionException

import static io.xh.hoist.HoistFilter.REQUEST_SPAN_ATTR

class ExceptionHandlerSpec extends Specification {

    @Subject
    def handler = new ExceptionHandler()

    def 'httpStatus is #expected for #ex.class.simpleName'() {
        expect:
        handler.getHttpStatus(ex) == expected

        where:
        ex                                        | expected
        new NotAuthorizedException()              | 403
        new NotAuthenticatedException()           | 401
        new NotFoundException()                   | 404
        new HttpException('x', null, 418)         | 418
        new ExternalHttpException('x', null, 502) | 500
        new ExternalHttpException('x', null)      | 500
        new RoutineRuntimeException('x')          | 400
        new DataNotAvailableException()           | 400
        new SessionMismatchException()            | 400
        new InstanceNotAvailableException('x')    | 400
        new RuntimeException('x')                 | 500
    }

    def 'httpStatus falls back to 500 for HttpException with null status'() {
        expect:
        handler.getHttpStatus(new HttpException('x', null, null)) == 500
    }

    def 'summaryText is #expected'() {
        expect:
        handler.summaryTextForThrowable(ex) == expected

        where:
        ex                                                                | expected
        new RuntimeException('boom')                                      | 'boom [RuntimeException]'
        new RuntimeException()                                            | '[RuntimeException]'
        new RuntimeException('outer', new IOException('inner'))           | 'outer [RuntimeException] caused by inner [IOException]'
        new RuntimeException('a', new IOException('b', new IllegalStateException('c'))) | 'a [RuntimeException] caused by b [IOException]'
        new ExecutionException(new IOException('inner'))                  | 'inner [IOException]'
        new ExecutionException('wrapper', null)                           | 'wrapper [ExecutionException]'
    }

    def 'isRoutine is #expected for #ex.class.simpleName'() {
        expect:
        handler.isRoutine(ex) == expected

        where:
        ex                                                                                                  | expected
        new RoutineRuntimeException('x')                                                                    | true
        new NotAuthorizedException()                                                                        | true
        new grails.validation.ValidationException('m', new BeanPropertyBindingResult(new Object(), 'obj')) | true
        new RuntimeException()                                                                              | false
        new NotFoundException()                                                                             | false
    }

    def 'routine exceptions are logged at debug, others at error'() {
        given:
        def log = Mock(LogSupport)

        when:
        handler.handleException(new RoutineRuntimeException('r'), null, log, null)

        then:
        1 * log.logDebug(_ as RoutineRuntimeException)
        0 * log.logError(*_)

        when:
        handler.handleException(new RuntimeException('e'), null, log, null)

        then:
        1 * log.logError(_ as RuntimeException)
        0 * log.logDebug(*_)
    }

    def 'preprocess unwraps #ex.class.simpleName to #expected.class.simpleName'() {
        expect:
        handler.preprocess(ex).is(expected)

        where:
        ex                                                                   | expected
        new InvocationTargetException(ERROR)                                 | ERROR
        new ServletException('x', ERROR)                                     | ERROR
        new InvokerInvocationException(ROUTINE)                              | ROUTINE
        new InvokerInvocationException(new InvocationTargetException(ERROR)) | ERROR
        new InvocationTargetException(null)                                  | ex
        new ServletException('x', ROUTINE)                                   | ex
        new ServletException('x')                                            | ex
        ROUTINE                                                              | ROUTINE
    }

    def 'logMessage defaults to the bound action name, and only when absent'() {
        given:
        def log = Mock(LogSupport)
        def ex = new RuntimeException('e')
        def request = new MockHttpServletRequest().tap {
            setAttribute(GrailsApplicationAttributes.ACTION_NAME_ATTRIBUTE, 'doThing')
        }
        WebUtils.storeGrailsWebRequest(new GrailsWebRequest(request, new MockHttpServletResponse(), new MockServletContext()))

        when:
        handler.handleException(ex, null, log, null)

        then:
        1 * log.logError({ it == [_action: 'doThing'] }, ex)

        when:
        handler.handleException(ex, null, log, 'explicit')

        then:
        1 * log.logError('explicit', ex)

        cleanup:
        RequestContextHolder.resetRequestAttributes()
    }

    def 'logMessage is passed ahead of the exception'() {
        given:
        def log = Mock(LogSupport)
        def ex = new RuntimeException('e')

        when:
        handler.handleException(ex, null, log, 'context')

        then:
        1 * log.logError('context', ex)
    }

    def 'response is rendered with status, content type and json body'() {
        given:
        def out = new StringWriter()
        def response = Mock(HttpServletResponse) {
            isCommitted() >> false
            getWriter() >> new PrintWriter(out)
        }
        def ex = new NotAuthorizedException()

        when:
        handler.handleException(ex, response, null, null)

        then:
        1 * response.setStatus(403)
        1 * response.setContentType('application/json')
        1 * response.flushBuffer()
        out.toString() == JSONSerializer.serialize(ex)
    }

    def 'records a rendered exception on the request span'() {
        given:
        def span = Mock(SpanRef)
        def ex = new RuntimeException('e')
        def request = new MockHttpServletRequest().tap { setAttribute(REQUEST_SPAN_ATTR, span) }

        when:
        handler.handleException(ex, new MockHttpServletResponse(), null, null, request)

        then:
        1 * span.recordException(ex)
    }

    def 'finds the request span via the thread-bound web request when none is passed'() {
        given:
        def span = Mock(SpanRef)
        def ex = new RuntimeException('e')
        def request = new MockHttpServletRequest().tap { setAttribute(REQUEST_SPAN_ATTR, span) }
        WebUtils.storeGrailsWebRequest(new GrailsWebRequest(request, new MockHttpServletResponse(), new MockServletContext()))

        when:
        handler.handleException(ex, new MockHttpServletResponse(), null, null)

        then:
        1 * span.recordException(ex)

        cleanup:
        RequestContextHolder.resetRequestAttributes()
    }

    def 'does not touch the span when nothing is rendered'() {
        given:
        def span = Mock(SpanRef)
        def request = new MockHttpServletRequest().tap { setAttribute(REQUEST_SPAN_ATTR, span) }

        when:
        handler.handleException(new RuntimeException('e'), null, null, null, request)

        then:
        0 * span.recordException(_)
    }

    def 'tolerates no request and no thread-bound web request'() {
        given:
        RequestContextHolder.resetRequestAttributes()

        when:
        handler.handleException(new RuntimeException('e'), new MockHttpServletResponse(), null, null)

        then:
        noExceptionThrown()
    }

    def 'committed response is left untouched'() {
        given:
        def response = Mock(HttpServletResponse) {
            isCommitted() >> true
        }

        when:
        handler.handleException(new RuntimeException('e'), response, null, null)

        then:
        0 * response.setStatus(_)
        0 * response.getWriter()
        0 * response.flushBuffer()
    }

    def 'falls back to instance logger when hoist logging fails'() {
        given:
        def logger = Mock(Logger)
        def log = Mock(LogSupport) {
            getInstanceLog() >> logger
        }

        when:
        handler.handleException(new RuntimeException('e'), null, log, null)

        then:
        1 * log.logError(_) >> { throw new IllegalStateException('logging broke') }
        1 * logger.error('e')
        1 * logger.error('Hoist Logging failed: logging broke')
    }

    def 'subclass can override template methods'() {
        given:
        def replacement = new IllegalArgumentException('replaced')
        def custom = new ExceptionHandler() {
            protected Throwable preprocess(Throwable t) { replacement }
            protected boolean shouldLogDebug(Throwable t) { true }
        }
        def log = Mock(LogSupport)

        when:
        custom.handleException(new RuntimeException('orig'), null, log, null)

        then:
        1 * log.logDebug(replacement)
        0 * log.logError(*_)
    }

    //------------------------
    // Implementation
    //------------------------
    private static final Error ERROR = new LinkageError('linkage')
    private static final Exception ROUTINE = new RoutineRuntimeException('routine')
}
