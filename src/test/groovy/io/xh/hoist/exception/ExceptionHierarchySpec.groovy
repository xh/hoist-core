/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.exception

import spock.lang.Specification

class ExceptionHierarchySpec extends Specification {

    def 'built-in exception defaults for #ex.class.simpleName'() {
        expect:
        ex.message == message
        ex.statusCode == status
        (ex instanceof RoutineException) == routine

        where:
        ex                                | message             | status | routine
        new NotAuthorizedException()      | 'Not Authorized'    | 403    | true
        new NotAuthenticatedException()   | 'Not Authenticated' | 401    | true
        new NotFoundException()           | 'Not Found'         | 404    | false
        new NotFoundException('nope')     | 'nope'              | 404    | false
        new HttpException('x', null, 418) | 'x'                 | 418    | false
    }

    def 'routine runtime exception family: #ex.class.simpleName'() {
        expect:
        ex instanceof RoutineRuntimeException
        ex instanceof RoutineException
        ex.message == message

        where:
        ex                                     | message
        new RoutineRuntimeException('r')       | 'r'
        new DataNotAvailableException()        | 'Data not available'
        new InstanceNotAvailableException('a') | 'a'
        new InstanceNotFoundException('f')     | 'f'
    }

    def 'session mismatch is routine with default message'() {
        when:
        def ex = new SessionMismatchException()

        then:
        ex instanceof RoutineException
        ex.message == 'Client username does not match current session user.'
    }

    def 'external http exception keeps cause, optional status and is not routine'() {
        given:
        def cause = new IOException('io')

        when:
        def ex = new ExternalHttpException('x', cause)

        then:
        ex.cause.is(cause)
        ex.statusCode == null
        !(ex instanceof RoutineException)
        new ExternalHttpException('x', cause, 502).statusCode == 502
    }

    def 'cluster execution exception records cause name without chaining cause'() {
        when:
        def ex = new ClusterExecutionException('msg', new IllegalStateException('inner'))

        then:
        ex.message == 'msg'
        ex.causeName == 'IllegalStateException'
        ex.cause == null
    }
}
