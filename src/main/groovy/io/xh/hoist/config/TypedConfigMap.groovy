/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.config

import io.xh.hoist.json.JSONFormat
import io.xh.hoist.log.LogSupport

import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType

/**
 * Base class for typed representations of Hoist JSON soft-config map values.
 *
 * Subclasses declare their properties (with optional default initializers) and must call
 * {@link #init} from their own constructor body so that declared defaults run first and
 * supplied values overlay on top.
 *
 * <pre>
 * {@code
 * class MyConfig extends TypedConfigMap {
 *     String getConfigName() { 'myAppConfig' }
 *
 *     boolean enabled = false       // default applied when key is missing from map
 *     String endpoint
 *
 *     MyConfig(Map args) { init(args) }
 * }
 * }
 * </pre>
 *
 * Unknown keys are logged at WARN and ignored — stale or mistyped entries in soft config
 * are surfaced without breaking startup.
 *
 * Nesting is supported: a declared property whose type extends `TypedConfigMap` is populated
 * recursively, and a `List<Foo>` where `Foo` extends `TypedConfigMap` converts each supplied
 * map to a `Foo`. Nested typed subclasses should leave {@link #getConfigName()} as null —
 * it's only required on top-level classes loaded via `ConfigService.getTypedConfig(Class)`.
 */
abstract class TypedConfigMap implements LogSupport, JSONFormat {

    /**
     * Name of the top-level `AppConfig` (JSON-type) that supplies this object's values.
     *
     * Required for classes loaded via `ConfigService.getTypedConfig(Class)`. Nested
     * typed subclasses (declared as properties on a parent config class) leave this as null.
     */
    String getConfigName() { null }

    /**
     * Assign values from `args` onto matching declared properties.
     *
     *  - Matching property of a `TypedConfigMap` subtype and value is a Map → recursively
     *    populate the existing nested instance, preserving its declared defaults for any
     *    keys not supplied.
     *  - Matching property declared as `List<Foo>` where `Foo` extends `TypedConfigMap` and
     *    value is a List of Maps → convert each element to a `Foo` via its Map constructor.
     *  - Matching property of any other type → assign directly.
     *  - No matching property → log at WARN and ignore.
     */
    protected void init(Map args) {
        args?.each { k, v ->
            def key = k as String
            if (!this.hasProperty(key)) {
                logWarn("Unknown key '$key' for ${getClass().simpleName}" + (configName ? " (config '$configName')" : ''))
                return
            }

            def propType = this.metaClass.getMetaProperty(key).type

            if (TypedConfigMap.isAssignableFrom(propType) && v instanceof Map) {
                def existing = this[key] as TypedConfigMap
                if (existing != null) {
                    existing.init(v)
                } else {
                    this[key] = propType.getDeclaredConstructor(Map).newInstance(v)
                }
            } else if (List.isAssignableFrom(propType) && v instanceof List) {
                def elementType = getListElementType(key)
                if (elementType && TypedConfigMap.isAssignableFrom(elementType)) {
                    this[key] = v.collect { item ->
                        item instanceof Map
                            ? elementType.getDeclaredConstructor(Map).newInstance(item)
                            : item
                    }
                } else {
                    this[key] = v
                }
            } else {
                this[key] = v
            }
        }
    }

    /**
     * Shallow map of declared properties on this instance. Nested `TypedConfigMap` values
     * (including lists of them) are left as-is — Jackson's `JSONFormatSerializer` picks
     * up the `JSONFormat` interface and recurses naturally when rendering to JSON.
     *
     * Used by Hoist's JSON serializer to emit fully populated (default-filled) payloads to
     * clients for `clientVisible` typed configs, and by
     * `ConfigService.ensureRequiredConfigsCreated` to compare typed-class defaults against
     * each config's BootStrap `defaultValue`.
     */
    Map formatForJSON() {
        def result = [:]
        // Walk the class hierarchy so that fields declared on intermediate subclasses are
        // included — supports patterns like `SpecificConfig extends AbstractConfig extends TypedConfigMap`.
        def cls = getClass()
        while (cls && cls != TypedConfigMap && cls != Object) {
            cls.declaredFields
                .findAll { !it.synthetic && !Modifier.isStatic(it.modifiers) }
                .each { field -> result[field.name] = this[field.name] }
            cls = cls.superclass
        }
        return result
    }

    protected Class getListElementType(String fieldName) {
        def cls = getClass()
        // Walk the hierarchy so list fields declared on intermediate subclasses are found.
        while (cls && cls != TypedConfigMap && cls != Object) {
            def field = cls.declaredFields.find { it.name == fieldName }
            if (field) {
                def genericType = field.genericType
                if (genericType instanceof ParameterizedType) {
                    def types = ((ParameterizedType) genericType).actualTypeArguments
                    if (types.length == 1 && types[0] instanceof Class) {
                        return (Class) types[0]
                    }
                }
                return null
            }
            cls = cls.superclass
        }
        return null
    }
}
