/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2019 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.exception

/**
 * Exception for use when URL requested does not match any known pattern.
 */
class NotFoundException extends RuntimeException {
    NotFoundException(String message = "Not Found") {
        super(message)
    }
}
