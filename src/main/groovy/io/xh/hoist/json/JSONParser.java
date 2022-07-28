/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2022 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.json;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import static com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS;
import static com.fasterxml.jackson.databind.DeserializationFeature.READ_DATE_TIMESTAMPS_AS_NANOSECONDS;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;


/**
 * Hoist wrapper around the Jackson library for JSON parsing into java objects.
 */
public class JSONParser {

    private static ObjectMapper mapper;
    private static ObjectMapper validateMapper;

    static {
        SimpleModule javaTimeModule = new JavaTimeModule();

        mapper = new ObjectMapper()
                .registerModule(javaTimeModule)
                .disable(READ_DATE_TIMESTAMPS_AS_NANOSECONDS);

        validateMapper = new ObjectMapper()
                .registerModule(javaTimeModule)
                .disable(READ_DATE_TIMESTAMPS_AS_NANOSECONDS)
                .enable(FAIL_ON_TRAILING_TOKENS);
    }

    /**
     * Parse a String representing a JSON Object to a java representation.
     */
    public static Map parseObject(String s) throws IOException {
        if (s == null || s.isEmpty()) return null;
        return mapper.readValue(s, new TypeReference<Map<String, Object>>() {});
    }

    /**
     * Parse an InputStream representing a JSON Object to a java representation.
     */
    public static Map parseObject(InputStream s) throws IOException {
        if (s == null) return null;
        return mapper.readValue(s, new TypeReference<Map<String, Object>>() {});
    }

    /**
     * Parse a String representing a JSON Array to a java representation.
     */
    public static List parseArray(String s) throws IOException {
        if (s == null || s.isEmpty()) return null;
        return mapper.readValue(s, new TypeReference<List>() {});
    }

    /**
     * Parse an InputStream representing a JSON Array to a java representation.
     */
    public static List parseArray(InputStream s) throws IOException {
        if (s == null) return null;
        return mapper.readValue(s, new TypeReference<List>() {});
    }

    /**
     * Parse a string representing either a JSON Array or a JSON Object to a java representation.
     */
    public static Object parseObjectOrArray(String s) throws IOException {
        if (s == null || s.isEmpty()) return null;
        s = s.trim();
        return s.startsWith("[") ? parseArray(s) : parseObject(s);
    }

    /**
     * Return true if a String represents valid JSON
     */
    public static boolean validate(String s) {
        if (s == null) return true;
        try {
            validateMapper.readTree(s);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
