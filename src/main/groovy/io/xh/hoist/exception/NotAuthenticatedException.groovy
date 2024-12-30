/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2025 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.exception

import static org.apache.hc.core5.http.HttpStatus.SC_UNAUTHORIZED

/**
 * Exception for use when the user making the request is not successfully authenticated,
 *
 * This exception is thrown by Hoist's {@link io.xh.hoist.security.BaseAuthenticationService} if
 * an incoming request cannot be successfully Authenticated.
 *
 * Instances of this exception will be sent to clients with HttpStatus 401 ('Unauthorized').
 */
class NotAuthenticatedException extends HttpException implements RoutineException {
    NotAuthenticatedException(String s = 'Not Authenticated') {
        super(s, null, SC_UNAUTHORIZED)
    }
}
