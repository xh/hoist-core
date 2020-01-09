/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2020 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.exception

import io.xh.hoist.util.Utils

/**
 * Exception for use when user input fails GORM validation
 */
class ValidationException extends RuntimeException implements RoutineException {
    ValidationException(grails.validation.ValidationException ex) {
        super(parseMessage(ex), ex)
    }

    private String parseMessage(grails.validation.ValidationException ex) {
        def msgSrc = Utils.appContext.messageSource
        return ex.errors.allErrors
                .collect {msgSrc.getMessage(it, Locale.US)}
                .join(' | ')
    }
}
