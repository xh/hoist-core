/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
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
 *
 * The cached JSON is transient and so *not* included when instances are serialized into Hazelcast
 * structures or cross-instance call results - shipping it would duplicate data already carried by
 * the object's own fields. Each instance rebuilds it lazily on first render.
 */
abstract public class JSONFormatCached {

    private transient volatile String _cache = null;

    public JSONFormatCached() { }

    abstract protected Object formatForJSON();

    public String getCachedJSON() throws JsonProcessingException {
        // Efficient double-checked locking -- lock on cold path only.
        String ret = _cache;
        if (ret == null) {
            synchronized (this) {
                ret = _cache;
                if (ret == null) {
                    ret = _cache = JSONSerializer.serialize(this.formatForJSON());
                }
            }
        }
        return ret;
    }
}
