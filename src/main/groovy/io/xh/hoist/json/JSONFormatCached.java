/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2019 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.json;

import com.fasterxml.jackson.core.JsonProcessingException;

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