/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.test

import groovy.transform.CompileStatic
import io.xh.hoist.config.AppConfig
import io.xh.hoist.config.ConfigService
import io.xh.hoist.config.TypedConfigMap
import io.xh.hoist.json.JSONParser
import io.xh.hoist.util.Utils

import java.util.concurrent.ConcurrentHashMap

/**
 * Map-backed {@link ConfigService} for tests - soft config values are set directly in code rather
 * than read from the `AppConfig` database table.
 *
 * <pre>
 * hoist.configService.set('maxRows', 100).set('endpoints', [a: 'http://a'])
 * </pre>
 *
 * Mirrors the missing-value contract of the real service: reading an unset config throws, unless
 * a non-null `notFoundValue` is supplied. Values are coerced to the type of the getter used, and
 * JSON configs may be supplied either as a Map/List or as a JSON string. Derived methods inherited
 * from `ConfigService` (e.g. `getStringList`, `getStringIfSet`) work as normal, as they delegate
 * to the overridden getters.
 *
 * Instance-config overrides, and the persistence/admin features of the real service, are not
 * modelled. Every public `get*`/`has*` method of `ConfigService` must be overridden here - this is
 * enforced by hoist-core's own test suite.
 */
@CompileStatic
class TestConfigService extends ConfigService {

    private final Map<String, Object> values = new ConcurrentHashMap<String, Object>()
    private final Map<String, Class<? extends TypedConfigMap>> typedClasses = new ConcurrentHashMap<>()
    private final Map<Class<? extends TypedConfigMap>, String> typedNames = new ConcurrentHashMap<>()

    //-------------------------
    // Test setup API
    //-------------------------
    /** Set a config value. A null value removes the config. Returns this service for chaining. */
    TestConfigService set(String name, Object value) {
        value == null ? values.remove(name) : values.put(name, value)
        this
    }

    /** Set multiple config values at once. */
    TestConfigService setAll(Map<String, ?> newValues) {
        newValues.each { k, v -> set(k, v) }
        this
    }

    /** Remove a config. */
    TestConfigService remove(String name) {
        values.remove(name)
        this
    }

    /** Remove all configs and typed class registrations. */
    TestConfigService clear() {
        values.clear()
        typedClasses.clear()
        typedNames.clear()
        this
    }

    /** Bind a `TypedConfigMap` subclass to a config name, enabling `getObject(clazz)`. */
    TestConfigService registerTypedConfig(String name, Class<? extends TypedConfigMap> clazz) {
        typedClasses[name] = clazz
        typedNames[clazz] = name
        this
    }

    //-------------------------
    // ConfigService overrides
    //-------------------------
    @Override
    String getString(String name, String notFoundValue = null) {
        def v = lookup(name, notFoundValue)
        v == null ? null : v.toString()
    }

    @Override
    Integer getInt(String name, Integer notFoundValue = null) {
        def v = lookup(name, notFoundValue)
        v instanceof Number ? ((Number) v).intValue() : (v == null ? null : Integer.valueOf(v.toString()))
    }

    @Override
    Long getLong(String name, Long notFoundValue = null) {
        def v = lookup(name, notFoundValue)
        v instanceof Number ? ((Number) v).longValue() : (v == null ? null : Long.valueOf(v.toString()))
    }

    @Override
    Double getDouble(String name, Double notFoundValue = null) {
        def v = lookup(name, notFoundValue)
        v instanceof Number ? ((Number) v).doubleValue() : (v == null ? null : Double.valueOf(v.toString()))
    }

    @Override
    Boolean getBool(String name, Boolean notFoundValue = null) {
        def v = lookup(name, notFoundValue)
        v instanceof Boolean ? (Boolean) v : (v == null ? null : Utils.parseBooleanStrict(v.toString()))
    }

    @Override
    Map getMap(String name, Map notFoundValue = null) {
        def v = lookup(name, notFoundValue)
        v instanceof Map ? (Map) v : (v == null ? null : JSONParser.parseObject(v.toString()))
    }

    @Override
    List getList(String name, List notFoundValue = null) {
        def v = lookup(name, notFoundValue)
        v instanceof List ? (List) v : (v == null ? null : JSONParser.parseArray(v.toString()))
    }

    @Override
    String getPwd(String name, String notFoundValue = null) {
        getString(name, notFoundValue)
    }

    @Override
    <T extends TypedConfigMap> T getObject(Class<T> clazz) {
        String name = typedNames[clazz]
        if (!name) {
            throw new RuntimeException(
                "${clazz.simpleName} is not registered as a typedClass - call registerTypedConfig() to make it loadable via getObject()"
            )
        }
        clazz.getDeclaredConstructor(Map).newInstance(getMap(name, [:]))
    }

    @Override
    boolean hasConfig(String name) {
        values.containsKey(name)
    }

    @Override
    Map getForAdminStats(String... names) {
        names.toList().collectEntries { String it -> [it, values[it]] }
    }

    /** Client-visible configs are not modelled - always empty. */
    @Override
    Map getClientConfig() {
        [:]
    }

    @Override
    Class<? extends TypedConfigMap> getTypedClass(String name) {
        typedClasses[name]
    }

    /** Admin console support is not modelled - always null. */
    @Override
    Object getResolvedConfigValue(AppConfig config) {
        null
    }

    /** Admin console support is not modelled - always null. */
    @Override
    Object getDefaultConfigValue(AppConfig config) {
        null
    }

    /** No-op - there is no cluster topic to notify. */
    @Override
    void fireConfigChanged(AppConfig obj) {}

    //-------------------------
    // Implementation
    //-------------------------
    private Object lookup(String name, Object notFoundValue) {
        def ret = values[name]
        if (ret != null) return ret
        if (notFoundValue != null) return notFoundValue
        throw new RuntimeException("No config found with name: [$name]")
    }
}
