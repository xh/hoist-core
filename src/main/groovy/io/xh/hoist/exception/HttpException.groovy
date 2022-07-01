/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2022 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.exception

/**
 * An Exception associated with an HTTP error code.
 */
class HttpException extends RuntimeException {

    Integer statusCode

    HttpException(String msg, Throwable cause, Integer statusCode) {
        super(msg, cause)
        this.statusCode = statusCode
    }
}
