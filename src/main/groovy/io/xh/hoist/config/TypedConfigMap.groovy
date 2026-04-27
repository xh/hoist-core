/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.config

import io.xh.hoist.json.JSONFormat
import io.xh.hoist.log.LogSupport

import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.util.concurrent.ConcurrentHashMap

/**
 * Base class for typed representations of Hoist JSON soft-config map values.
 *
 * Subclasses declare their properties and must call {@link #init} from their own constructor body.
 *
 * <pre>
 * {@code
 * class MyConfig extends TypedConfigMap {
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
 * recursively, and `List<Foo>` / `Map<String, Foo>` properties (where `Foo` extends
 * `TypedConfigMap`) convert each supplied map to a `Foo`. Top-level subclasses are bound to a
 * backing `AppConfig` name by registering them in `ensureRequiredConfigsCreated` (`typedClass:`
 * entry); they can then be loaded via `ConfigService.getObject(Class)`.
 */
abstract class TypedConfigMap implements LogSupport, JSONFormat {

    private static final Set<String> warnedUnknownKeys = ConcurrentHashMap.newKeySet()

    // Cache of the declared config fields for each subclass — walked once, lazily, then reused
    // for both inbound binding (`init`) and outbound serialization (`formatForJSON`).
    private static final Map<Class, Map<String, Field>> fieldsByClass = new ConcurrentHashMap<>()

    // Primitive → boxed map for `checkAssignable` — without it, Groovy coerces anything
    // truthy/falsy into a `boolean` field (so `5` silently becomes `true`).
    private static final Map<Class, Class> PRIMITIVE_BOXED = [
        (boolean): Boolean, (int): Integer, (long): Long, (double): Double,
        (float): Float, (short): Short, (byte): Byte, (char): Character
    ]

    /**
     * Assign values from `args` onto matching declared properties. Nested `TypedConfigMap`
     * properties (including in `List<Foo>` and `Map<String, Foo>` shapes) are constructed
     * recursively. Unknown keys are logged at WARN and ignored.
     */
    protected void init(Map args) {
        // Capture outside the closure — inside, getClass() resolves to the closure, not `this`.
        String simpleName = getClass().simpleName
        def fields = configFields()
        args?.each { k, v ->
            def key = k as String
            def field = fields[key]
            if (!field) {
                // Deduplicated so high-frequency timer-driven config reads don't spam the log.
                String dedupKey = "${simpleName}:${key}"
                if (warnedUnknownKeys.add(dedupKey)) {
                    logWarn("Unknown key '$key' for $simpleName")
                }
                return
            }

            def propType = field.type

            if (TypedConfigMap.isAssignableFrom(propType) && v instanceof Map) {
                def existing = this[key] as TypedConfigMap
                if (existing != null) {
                    existing.init(v)
                } else {
                    this[key] = propType.getDeclaredConstructor(Map).newInstance(v)
                }
            } else if (List.isAssignableFrom(propType) && v instanceof List) {
                def elementType = TypedConfigMap.typeArg(field, 0, 1)
                if (elementType && TypedConfigMap.isAssignableFrom(elementType)) {
                    this[key] = v.collect { item ->
                        item instanceof Map
                            ? elementType.getDeclaredConstructor(Map).newInstance(item)
                            : item
                    }
                } else {
                    this[key] = v
                }
            } else if (Map.isAssignableFrom(propType) && v instanceof Map) {
                def valueType = TypedConfigMap.typeArg(field, 1, 2)
                if (valueType && TypedConfigMap.isAssignableFrom(valueType)) {
                    this[key] = v.collectEntries { mk, mv ->
                        [(mk): mv instanceof Map
                            ? valueType.getDeclaredConstructor(Map).newInstance(mv)
                            : mv]
                    }
                } else {
                    this[key] = v
                }
            } else {
                TypedConfigMap.checkAssignable(simpleName, key, propType, v)
                this[key] = v
            }
        }
    }

    // Strict type guard — Groovy's setter would silently coerce wrong-type values into
    // `boolean`/`String` fields. Number-to-Number widening is allowed.
    private static void checkAssignable(String typeName, String key, Class propType, Object v) {
        if (v == null) return
        Class boxed = PRIMITIVE_BOXED[propType] ?: propType
        if (boxed.isInstance(v)) return
        if (Number.isAssignableFrom(boxed) && v instanceof Number) return
        throw new IllegalArgumentException(
            "Field '$key' on $typeName expects ${boxed.simpleName} but got ${v.getClass().simpleName}"
        )
    }

    Map formatForJSON() {
        // Field-driven serialization.  Emits exactly the declared config shape.
        configFields().collectEntries { name, field -> [name, this[name]] }
    }

    // Walk the class hierarchy from `this` up to (but not including) `TypedConfigMap` to collect
    // declared, non-static, non-synthetic fields. Subclass fields take precedence over same-named
    // fields on intermediate ancestors. Cached per-class.
    private Map<String, Field> configFields() {
        fieldsByClass.computeIfAbsent(getClass()) { Class cls ->
            Map<String, Field> result = [:]
            while (cls && cls != TypedConfigMap && cls != Object) {
                cls.declaredFields
                    .findAll { !it.synthetic && !Modifier.isStatic(it.modifiers) }
                    .each { field -> result.putIfAbsent(field.name, field) }
                cls = cls.superclass
            }
            result
        }
    }

    private static Class typeArg(Field field, int index, int expectedCount) {
        def genericType = field.genericType
        if (genericType instanceof ParameterizedType) {
            def types = ((ParameterizedType) genericType).actualTypeArguments
            if (types.length == expectedCount && types[index] instanceof Class) {
                return (Class) types[index]
            }
        }
        return null
    }
}
