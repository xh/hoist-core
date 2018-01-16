/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2018 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.json

import groovy.transform.CompileStatic

/**
 * Trait used by a class to indicate its structure for serialization by the JSON converter.
 */
@CompileStatic
trait JSONFormat {

    private JSONCached _cache = null

    /**
     * Should the serialization of this object be cached and reused?
     *
     * Defaults to false.  Immutable objects that are expected
     * to be repeatedly converted to JSON may wish to return true.
     */
    boolean cacheJSON() {false}
    
    /**
     * Specify a desired format for JSON serialization.
     * Main entry point.
     *
     * @return any object that can be handled by the JSON converter.
     */
    abstract Object formatForJSON()

    /**
     * Called internally by framework converter.
     *
     * Subclasses should implement formatForJSON instead.
     */
    Object getObjectForConverter() {
        if (!cacheJSON()) return formatForJSON()
        
        if (!_cache) {
            _cache = new JSONCached(formatForJSON())
        }
        return _cache
    }
    
}
