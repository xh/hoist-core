/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2021 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.json.serializer;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;

import java.io.IOException;

public class DoubleSerializer extends StdSerializer<Double> {

    public DoubleSerializer() {
        this(null);
    }

    public DoubleSerializer(Class<Double> t) {
        super(t);
    }

    @Override
    public void serialize(Double value, JsonGenerator jgen, SerializerProvider provider) throws IOException {
        if (Double.isFinite(value)) {
            jgen.writeNumber(value);
        } else {
            jgen.writeNull();
        }
    }

}
