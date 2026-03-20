/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.json.serializer;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.ser.std.StdSerializer
import groovy.transform.CompileStatic
import io.opentelemetry.api.trace.Span
import io.xh.hoist.exception.RoutineException
import io.xh.hoist.json.JSONFormat

@CompileStatic
class ThrowableSerializer extends StdSerializer<Throwable> {

   ThrowableSerializer() {
        this(null)
   }

    ThrowableSerializer(Class<Throwable> t) {
        super(t)
    }

    @Override
    void serialize(Throwable t, JsonGenerator jgen, SerializerProvider provider) throws IOException {
        def ret = t instanceof JSONFormat ?
            t.formatForJSON() :
            [
                name     : t.class.simpleName,
                message  : t.message,
                cause    : t.cause?.message,
                isRoutine: t instanceof RoutineException,
                traceId  : activeTraceId
            ].findAll { it.value }

        jgen.writeObject(ret)
    }

    private static String getActiveTraceId() {
        def ctx = Span.current()?.spanContext
        ctx?.valid ? ctx.traceId : null
    }
}
