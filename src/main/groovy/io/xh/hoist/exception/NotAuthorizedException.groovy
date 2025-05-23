/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2025 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.exception

import static org.apache.hc.core5.http.HttpStatus.SC_FORBIDDEN

/**
 * Exception for use when the authenticated user does not have access to the resource in question.
 *
 * This exception is thrown by Hoist's by {@link io.xh.hoist.security.AccessInterceptor} if an
 * authenticated user does not have roles required by a controller's `@Access` annotation.
 *
 * Applications may also throw this exception, or subclasses of it, directly in response to requests
 * they cannot fulfill due to authorization-related constraints.
 *
 * Instances of this exception will be sent to clients with HttpStatus 403 ('Forbidden').
 */
class NotAuthorizedException extends HttpException implements RoutineException {
    NotAuthorizedException(String s = 'Not Authorized') {
        super(s, null, SC_FORBIDDEN)
    }
}
