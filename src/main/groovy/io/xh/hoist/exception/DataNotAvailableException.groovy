/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2019 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.exception

/**
 * Exception for use when requested data is not currently available due to normal, expected
 * business conditions. A common example would be a well-formed request that queries data
 * before the server is able to calculate it - i.e. on startup or for a new business day.
 */
class DataNotAvailableException extends RuntimeException implements RoutineException {
    DataNotAvailableException(String s = 'Data not available') {
        super(s)
    }
}