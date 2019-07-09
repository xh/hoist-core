/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2019 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.json;

import com.fasterxml.jackson.core.JsonProcessingException;

/**
 * Superclass to provide support for cached JSON serialization. Consider for classes that:
 *
 *      + Are likely to have the same instances serialized multiple times (e.g. a cached resultset
 *        provided to multiple users).
 *      + Have final / immutable properties that won't change after the first serialization.
 *      + Are serialized in bulk, contain large collections, or are otherwise performance-sensitive.
 */
abstract public class JSONFormatCached {

    private String _cache = null;

    public JSONFormatCached() { }

    abstract protected Object formatForJSON();

    public String getCachedJSON() throws JsonProcessingException {
        if (_cache == null) {
            _cache = JSONSerializer.serialize(this.formatForJSON());
        }
        return _cache;
    }
}