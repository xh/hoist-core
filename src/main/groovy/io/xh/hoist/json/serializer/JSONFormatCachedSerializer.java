/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2019 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.json.serializer;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import io.xh.hoist.json.JSONFormatCached;

import java.io.IOException;

public class JSONFormatCachedSerializer extends StdSerializer<JSONFormatCached> {

    public JSONFormatCachedSerializer() {
        this(null);
    }

    public JSONFormatCachedSerializer(Class<JSONFormatCached> t) {
        super(t);
    }

    @Override
    public void serialize(JSONFormatCached value, JsonGenerator jgen, SerializerProvider provider) throws IOException {
        jgen.writeRawValue(value.getCachedJSON());
    }
}