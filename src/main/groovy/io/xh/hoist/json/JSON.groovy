/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2020 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.json

import groovy.transform.CompileStatic

/**
 * Alias for Grails JSON converter. Used for parsing JSON strings to Objects in Groovy.
 *
 * In prior versions of this plugin, this class also supported customized serialization from Objects
 * to JSON. Applications should now use the Jackson-based JSONSerializer instead when writing JSON.
 *
 * This class is maintained here primarily for backwards compatibility; no future extensions or
 * modifications are currently planned.
 */
@CompileStatic
class JSON extends grails.converters.JSON {

    JSON(Object target) {
        super(target)
    }
}
