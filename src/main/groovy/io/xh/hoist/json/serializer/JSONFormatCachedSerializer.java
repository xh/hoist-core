/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.json.serializer;

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ser.std.StdSerializer;
import io.xh.hoist.json.JSONFormatCached;


public class JSONFormatCachedSerializer extends StdSerializer<JSONFormatCached> {

    public JSONFormatCachedSerializer() {
        this(null);
    }

    public JSONFormatCachedSerializer(Class<JSONFormatCached> t) {
        super(t);
    }

    @Override
    public void serialize(JSONFormatCached value, JsonGenerator jgen, SerializationContext context) {
        jgen.writeRawValue(value.getCachedJSON());
    }
}
