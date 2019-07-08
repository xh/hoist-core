/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2019 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.json

import groovy.transform.CompileStatic

/**
 * Alias for grails JSON converter.  Used for parsing JSON strings to an
 * object representation in Groovy.
 *
 * Note: For serialzing *to* a Json string, applications should use the
 * Jackson-based JSONSerializer instead.
 */
@CompileStatic
class JSON extends grails.converters.JSON {

    JSON(Object target) {
        super(target)
    }
}
