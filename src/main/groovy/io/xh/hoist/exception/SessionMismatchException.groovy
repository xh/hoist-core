/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2025 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.exception


/**
 * Exception for use when the user initiating a client request does not match the session user.
 */
class SessionMismatchException extends RuntimeException implements RoutineException {
    SessionMismatchException(String s = 'Client username does not match current session user.') {
        super(s)
    }
}
