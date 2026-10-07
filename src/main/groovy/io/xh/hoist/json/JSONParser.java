/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.json;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import static tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS;
import static tools.jackson.databind.cfg.DateTimeFeature.READ_DATE_TIMESTAMPS_AS_NANOSECONDS;

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
        // Jackson 2 defaults preserve Hoist's parsing behavior - e.g. lenient trailing tokens.
        mapper = JsonMapper.builderWithJackson2Defaults()
                .disable(READ_DATE_TIMESTAMPS_AS_NANOSECONDS)
                .build();

        validateMapper = JsonMapper.builderWithJackson2Defaults()
                .disable(READ_DATE_TIMESTAMPS_AS_NANOSECONDS)
                .enable(FAIL_ON_TRAILING_TOKENS)
                .build();
    }

    /**
     * Parse a String representing a JSON Object to a java representation.
     */
    public static Map parseObject(String s) {
        if (s == null || s.isEmpty()) return null;
        return mapper.readValue(s, new TypeReference<Map<String, Object>>() {});
    }

    /**
     * Parse an InputStream representing a JSON Object to a java representation.
     */
    public static Map parseObject(InputStream s) {
        if (s == null) return null;
        return mapper.readValue(s, new TypeReference<Map<String, Object>>() {});
    }

    /**
     * Parse a String representing a JSON Array to a java representation.
     */
    public static List parseArray(String s) {
        if (s == null || s.isEmpty()) return null;
        return mapper.readValue(s, new TypeReference<List>() {});
    }

    /**
     * Parse an InputStream representing a JSON Array to a java representation.
     */
    public static List parseArray(InputStream s) {
        if (s == null) return null;
        return mapper.readValue(s, new TypeReference<List>() {});
    }

    /**
     * Parse a string representing either a JSON Array or a JSON Object to a java representation.
     */
    public static Object parseObjectOrArray(String s) {
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
