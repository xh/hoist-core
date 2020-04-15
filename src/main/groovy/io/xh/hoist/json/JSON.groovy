/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2020 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.json

import groovy.transform.CompileStatic

/**
 * Alias for Grails JSON converter.
 *
 * DEPRECATED. This class is maintained here primarily for backwards compatibility; no future
 * extensions or modifications are currently planned. Applications should consider using either
 * JSONParser, or JSONSerializer API.  These classes uses the more modern and efficient Jackson
 * library.
 *
 * In prior versions of this plugin, this class also supported customized serialization from Objects
 * to JSON. Applications requiring this should use JSONSerializer instead.
 */
@CompileStatic
class JSON extends grails.converters.JSON {

    JSON(Object target) {
        super(target)
    }
}
