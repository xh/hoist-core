/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2025 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.exception

/**
 * Exception indicating an HTTP call to an external server failed.
 */
class ExternalHttpException extends HttpException {

    ExternalHttpException(String msg, Throwable cause, Integer statusCode = null) {
        super(msg, cause, statusCode)
    }
}
