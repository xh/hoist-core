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
 * The cached JSON is held in a transient field and is therefore *not* included when instances are
 * serialized into Hazelcast structures (replicated Cache/CachedValue, IMap, Topics) or returned
 * from cross-instance service calls. Each instance rebuilds its own copy lazily on first render -
 * shipping the string would duplicate data already carried by the object's own fields, inflating
 * both cluster traffic and per-instance heap.
 */
abstract public class JSONFormatCached {

    // Transient by design - see class-level comment. Volatile so that concurrent readers reliably
    // see a cache populated by another thread, rather than redundantly re-serializing.
    private transient volatile String _cache = null;

    public JSONFormatCached() { }

    abstract protected Object formatForJSON();

    public String getCachedJSON() throws JsonProcessingException {
        String ret = _cache;
        if (ret == null) {
            // Double-checked locking - only one thread serializes, while the warm path stays a
            // single volatile read. Locks on `this` rather than a dedicated lock object, which
            // would be null on instances deserialized by Kryo: it bypasses constructors - and so
            // field initializers - for classes lacking a no-arg constructor.
            synchronized (this) {
                ret = _cache;
                if (ret == null) {
                    ret = JSONSerializer.serialize(this.formatForJSON());
                    _cache = ret;
                }
            }
        }
        return ret;
    }
}
