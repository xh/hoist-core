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


public class FloatSerializer extends StdSerializer<Float> {

    public FloatSerializer() {
        this(null);
    }

    public FloatSerializer(Class<Float> t) {
        super(t);
    }

    @Override
    public void serialize(Float value, JsonGenerator jgen, SerializationContext context) {
        if (Float.isFinite(value)) {
            jgen.writeNumber(value);
        } else {
            jgen.writeNull();
        }
    }
}
