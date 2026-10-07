/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.exception

import io.xh.hoist.json.JSONSerializer
import io.xh.hoist.log.LogSupport
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.Logger
import org.springframework.validation.BeanPropertyBindingResult
import spock.lang.PendingFeature
import spock.lang.Specification
import spock.lang.Subject

import java.util.concurrent.ExecutionException

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

    @PendingFeature(reason = 'getHttpStatus unboxes a null statusCode on a plain HttpException and throws NPE instead of falling back to 500')
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
}
