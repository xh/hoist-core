/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2020 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.json;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;

/**
 * Hoist wrapper around the Jackson library for JSON parsing into java objects.
 */
public class JSONParser {

    private static ObjectMapper mapper = new ObjectMapper();

    /**
     * Parse a string representing a JSON Object string to a java/groovy representation.
     */
    public static Map parseObject(String str) throws Exception {
        if (str == null || str.isEmpty()) return null;
        return mapper.readValue(str, new TypeReference<Map<String, Object>>() {});
    }

    /**
     * Parse a string representing a JSON Array to a java/groovy representation.
     */
    public static List parseArray(String str) throws Exception {
        if (str == null || str.isEmpty()) return null;
        return mapper.readValue(str, new TypeReference<List>() {});
    }

}
