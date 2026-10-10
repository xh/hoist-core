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


public class DoubleSerializer extends StdSerializer<Double> {

    public DoubleSerializer() {
        this(null);
    }

    public DoubleSerializer(Class<Double> t) {
        super(t);
    }

    @Override
    public void serialize(Double value, JsonGenerator jgen, SerializationContext context) {
        if (Double.isFinite(value)) {
            jgen.writeNumber(value);
        } else {
            jgen.writeNull();
        }
    }

}
