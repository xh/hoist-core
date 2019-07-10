/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2019 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.exception

/**
 * Exception for use when the data requested by the client is not current available.
 */
class DataNotAvailableException extends RuntimeException implements RoutineException {
    DataNotAvailableException(String s = 'Data Not Available') {
        super(s)
    }
}