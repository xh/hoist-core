/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2025 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.exception

/**
 * Exception for use when the server instance is not yet ready for requests.
 */
class InstanceNotAvailableException extends RoutineRuntimeException {
    InstanceNotAvailableException(String s) {
        super(s)
    }
}
