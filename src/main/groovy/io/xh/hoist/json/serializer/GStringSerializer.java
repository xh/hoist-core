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
import groovy.lang.GString;


public class GStringSerializer extends StdSerializer<GString> {

    public GStringSerializer() {
        this(null);
    }

    public GStringSerializer(Class<GString> t) {
        super(t);
    }

    @Override
    public void serialize(GString value, JsonGenerator jgen, SerializationContext context) {
        jgen.writeString(value.toString());
    }
}
