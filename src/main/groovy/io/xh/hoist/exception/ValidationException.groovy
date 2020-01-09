/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2020 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.exception

/**
 * Exception for use when user input fails GORM validation
 */
class ValidationException extends RuntimeException implements RoutineException {
    ValidationException(String s) {
        super(s)
    }
}
