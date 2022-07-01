/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2022 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.json.serializer;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import groovy.lang.GString;

import java.io.IOException;

public class GStringSerializer extends StdSerializer<GString> {

    public GStringSerializer() {
        this(null);
    }

    public GStringSerializer(Class<GString> t) {
        super(t);
    }

    @Override
    public void serialize(GString value, JsonGenerator jgen, SerializerProvider provider) throws IOException {
        jgen.writeString(value.toString());
    }
}
