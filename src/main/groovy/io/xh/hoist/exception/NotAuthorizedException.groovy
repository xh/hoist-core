/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2019 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.exception

/**
 * Exception for use when the authenticated user does not have access to the resource in question.
 *
 * This exception is thrown by Hoist security - see io.xh.hoist.security.Access.
 * This exception, or subclasses of it, may be thrown directly by applications as well.
 * Instances of this exception will be associated with HttpStatus 404 ('Unauthorized') when sent to client.
 */
class NotAuthorizedException extends RuntimeException implements RoutineException {
    NotAuthorizedException(String s = 'Not Authorized') {
        super(s)
    }
}