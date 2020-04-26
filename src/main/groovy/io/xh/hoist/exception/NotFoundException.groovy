/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2020 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.exception

import static org.apache.hc.core5.http.HttpStatus.SC_NOT_FOUND

/**
 * Exception for use when URL requested does not match any known pattern.
 */
class NotFoundException extends HttpException {
    NotFoundException(String message = "Not Found") {
        super(message, null, SC_NOT_FOUND)
    }
}
