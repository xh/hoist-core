/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.test

import groovy.transform.CompileStatic
import io.xh.hoist.json.JSONParser
import io.xh.hoist.json.JSONSerializer

/**
 * JSON helpers for tests that use Hoist's own Jackson-based {@link JSONSerializer} and
 * {@link JSONParser}, to assert the actual wire format of `JSONFormat` objects, domain objects and
 * other values as clients receive them.
 *
 * Prefer these to `response.json` in Grails controller unit tests, which uses Grails' converters
 * rather than Hoist's serializer. Parse a rendered body with `HoistJson.parse(response.text)`.
 *
 * Note that custom Jackson modules registered via `JSONSerializer.registerModules()` are global and
 * append-only - register app modules once, e.g. in a static initializer, rather than per feature.
 */
@CompileStatic
final class HoistJson {

    private HoistJson() {}

    /** Serialize with Hoist's JSONSerializer. */
    static String serialize(Object o) {
        JSONSerializer.serialize(o)
    }

    /** Serialize with Hoist's JSONSerializer, then parse the result into Maps, Lists and scalars. */
    static Object roundTrip(Object o) {
        parse(serialize(o))
    }

    /** As {@link #roundTrip}, for a value expected to serialize to a JSON object. */
    static Map roundTripObject(Object o) {
        (Map) roundTrip(o)
    }

    /** As {@link #roundTrip}, for a value expected to serialize to a JSON array. */
    static List roundTripArray(Object o) {
        (List) roundTrip(o)
    }

    /** Parse a JSON object or array string with Hoist's JSONParser. */
    static Object parse(String json) {
        JSONParser.parseObjectOrArray(json)
    }
}
