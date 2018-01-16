/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2018 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.json

import org.grails.web.converters.exceptions.ConverterException
import org.grails.web.json.JSONException
import org.grails.web.json.JSONWriter

import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import groovy.transform.CompileStatic
import groovy.transform.CompileDynamic

/**
 * Overridden version of grails JSON converter.
 *
 * This extension has special handling for nulls, Dates, and the JSONCached and JSONFormat traits.  
 * It also outputs top-level primitives as valid JSON texts, following latest JSON specifications.  
 * Note that *parsing* of top-level primitives has not yet been implemented.
 */
@CompileStatic
class JSON extends grails.converters.JSON {

    JSON(Object target) {
        super(target)
    }

    void value(Object o) throws ConverterException {
        // 0) Special handling for top-level primitives
        if (writeTopLevelPrimitive(o)) {
           return
        }

        // 1) Recursive super implementation with enhancements for certain types
        try {
            switch (o) {
                case null:
                    writer.valueNull()
                    break
                case Date:
                    writer.value (((Date) o).getTime())
                    break
                case Instant:
                    writer.value (((Instant) o).toEpochMilli())
                    break
                case LocalDate:
                    writer.value (((LocalDate) o).format(DateTimeFormatter.BASIC_ISO_DATE))
                    break
                case JSONCached:
                    writeCached((JSONCached) o)
                    break
                case JSONFormat:
                    value(((JSONFormat)o).getObjectForConverter())
                    break
                default:
                    super.value(o)
            }
        } catch (JSONException e) {
            throw new ConverterException(e)
        }
    }

    @CompileDynamic
    private boolean writeTopLevelPrimitive(Object o) {
        if (writer.mode == JSONWriter.Mode.INIT) {
            def writer = writer.writer
            if (o instanceof String) {
                writer.write('"')
                writer.write(o.toString())
                writer.write('"')
                return true
            } else if (o == null || o instanceof Boolean || o instanceof Number) {
                writer.write(o.toString())
                return true
            }
        }
        return false
    }
    
    @CompileDynamic
    void writeCached(JSONCached o) {
        writer.append(o.cached)
    }
    
}
