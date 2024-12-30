/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2025 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.json.serializer;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import io.xh.hoist.json.JSONFormat;

import java.io.IOException;

public class JSONFormatSerializer extends StdSerializer<JSONFormat> {

    public JSONFormatSerializer() {
        this(null);
    }

    public JSONFormatSerializer(Class<JSONFormat> t) {
        super(t);
    }

    @Override
    public void serialize(JSONFormat value, JsonGenerator jgen, SerializerProvider provider) throws IOException {
        jgen.writeObject(value.formatForJSON());
    }
}

