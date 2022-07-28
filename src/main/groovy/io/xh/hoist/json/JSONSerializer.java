/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2022 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.json;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import static com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATE_TIMESTAMPS_AS_NANOSECONDS;

import groovy.lang.GString;
import io.xh.hoist.json.serializer.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static java.util.Arrays.asList;

/**
 * Hoist wrapper around the Jackson library for Json Serialization.
 *
 * This class provides a Hoist-customized instance of the standard Jackson serialization library.
 * Application that need to perform JSON serialization directly should use it to ensure that classes
 * such as Date, JSONFormat, and JSONFormatCached are serialized appropriately, according to Hoist
 * conventions.
 *
 * Applications should not typically need to use this object directly, but should rather rely on the
 * renderJSON() in BaseController, which will use this method, in combination with the JSONFormat
 * interface on app-specific domain objects and POJOs.
 */
public class JSONSerializer {

    private static ObjectMapper mapper;
    private static List<Module> registeredModules = new ArrayList<>();

    static {
        // JSR 310 standard
        SimpleModule javaTimeModule = new JavaTimeModule();

        // Hoist Conventional JSON Formats
        SimpleModule hoistModule = new SimpleModule();
        hoistModule.addSerializer(Date.class, new DateSerializer())
                .addSerializer(GString.class, new GStringSerializer())
                .addSerializer(JSONFormatCached.class, new JSONFormatCachedSerializer())
                .addSerializer(JSONFormat.class, new JSONFormatSerializer())
                .addSerializer(Double.class, new DoubleSerializer())
                .addSerializer(Float.class, new FloatSerializer());
        // ... plus one overwrite of JSR 310 standard
        hoistModule.addSerializer(LocalDate.class, new LocalDateSerializer());

        registerModules(javaTimeModule, hoistModule);
    }

    /**
     * Serialize an Object to JSON.  Main entry point.
     */
    public static String serialize(Object obj) throws JsonProcessingException {
        return mapper.writeValueAsString(obj);
    }

    /**
     * Serialize an Object to JSON with PrettyPrinting.
     */
    public static String serializePretty(Object obj) throws JsonProcessingException {
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(obj);
    }


    /**
     * Register a custom module for the Jackson serializer used by this class.
     *
     * Applications should use this method to add custom serializers or otherwise customize
     * the default JSON serialization.
     */
    public static void registerModules(Module  ...modules) {
        registeredModules.addAll(asList(modules));
        ObjectMapper newMapper = new ObjectMapper();
        newMapper.disable(WRITE_DATE_TIMESTAMPS_AS_NANOSECONDS);
        newMapper.registerModules(registeredModules);
        mapper = newMapper;
    }
}
