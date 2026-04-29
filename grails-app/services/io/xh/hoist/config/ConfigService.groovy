/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.config

import grails.compiler.GrailsCompileStatic
import grails.gorm.transactions.ReadOnly
import grails.gorm.transactions.Transactional
import groovy.transform.CompileDynamic
import io.xh.hoist.BaseService
import io.xh.hoist.config.impl.ConfigDriftService

import static io.xh.hoist.json.JSONSerializer.serializePretty

/**
 * Service to provide soft-configured `AppConfig` values to both server and client.
 *
 * Configs are managed via the Hoist Admin Console and persisted to the application database with
 * metadata to specify their type and client-side visibility. They are intended to be used for
 * any values that might need to differ across environments, be adjusted at runtime, or that are
 * generally unsuitable for hard-coding in the application source code.
 *
 * Note that the effective value of a config can be overridden by an "instance config" with the
 * same name. See {@link io.xh.hoist.util.InstanceConfigUtils} for more details on that system.
 * Also note that instance configs (and therefore AppConfig overrides) can be sourced from a
 * predefined yaml file, directory, and/or environment variables.
 *
 * Fires an `xhConfigChanged` event when a config value is updated.
 */
@GrailsCompileStatic
class ConfigService extends BaseService {

    ConfigDriftService configDriftService

    // Bidirectional registry of typed classes ↔ backing config names. Populated in `ensureRequiredConfigsCreated`.
    private final Map<String, Class<? extends TypedConfigMap>> configTypeByName = [:]
    private final Map<Class<? extends TypedConfigMap>, String> nameByConfigType = [:]

    String getString(String name, String notFoundValue = null) {
        return (String) getInternalByName(name, 'string', notFoundValue)
    }

    Integer getInt(String name, Integer notFoundValue = null) {
        return (Integer) getInternalByName(name, 'int', notFoundValue)
    }

    Long getLong(String name, Long notFoundValue = null) {
        return (Long) getInternalByName(name, 'long', notFoundValue)
    }

    Double getDouble(String name, Double notFoundValue = null) {
        return (Double) getInternalByName(name, 'double', notFoundValue)
    }

    Boolean getBool(String name, Boolean notFoundValue = null) {
        return (Boolean) getInternalByName(name, 'bool', notFoundValue)
    }

    Map getMap(String name, Map notFoundValue = null) {
        return (Map) getInternalByName(name, 'json', notFoundValue)
    }

    List getList(String name, List notFoundValue = null) {
        return (List) getInternalByName(name, 'json', notFoundValue)
    }

    String getPwd(String name, String notFoundValue = null) {
        return (String) getInternalByName(name, 'pwd', notFoundValue)
    }

    /**
     * Load a typed representation of a JSON soft config, with declared property defaults
     * applied for any keys missing from the stored value.
     *
     * The supplied class must extend {@link TypedConfigMap} and be registered against a
     * backing `AppConfig` name via a `typedClass:` entry on the {@link ConfigSpec} passed to
     * {@link #ensureRequiredConfigsCreated}. This is the preferred way to read structured configs —
     * it centralizes defaults and documentation on the typed class itself, rather than scattering
     * `?:` fallbacks across call sites.
     */
    <T extends TypedConfigMap> T getObject(Class<T> clazz) {
        String name = nameByConfigType[clazz]
        if (!name) {
            throw new RuntimeException(
                "${clazz.simpleName} is not registered as a typedClass — declare it via ensureRequiredConfigsCreated to be loadable via getObject()"
            )
        }
        return (T) clazz.getDeclaredConstructor(Map).newInstance(getMap(name, [:]))
    }


    /**
     * Return a map of all config values needed by client.
     * All passwords will be obscured.
     */
    @ReadOnly
    boolean hasConfig(String name) {
        return AppConfig.findByName(name, [cache: true]) != null
    }

    @ReadOnly
    Map getClientConfig() {
        def ret = [:]

        AppConfig.findAllByClientVisible(true, [cache: true]).each {
            AppConfig config = (AppConfig) it
            def name = config.name
            try {
                // Sanitize via `externalValue` as the single source of truth for client-bound
                // output — handles instance-config overrides, JSON parsing, and password obscuring
                def external = config.externalValue(obscurePassword: true, jsonAsObject: true)
                def typedClass = configTypeByName[name]
                ret[name] = (typedClass && external instanceof Map)
                    ? typedClass.getDeclaredConstructor(Map).newInstance(external)
                    : external
            } catch (Exception e) {
                logError("Skipping config '$name' — could not be prepared for client", e.message)
            }
        }

        return ret
    }

    /**
     * Return a map of specified config values, appropriate for display in admin client.
     * Note this may include configs that are not typically sent to clients
     * as specified by 'clientVisible'.  All passwords will be obscured, however.
     */
    @ReadOnly
    Map getForAdminStats(String... names) {
        return names.toList().collectEntries {
            def config = AppConfig.findByName(it, [cache: true])
            [it, config?.externalValue(obscurePassword: true, jsonAsObject: true)]
        }
    }

    /**
     * Parse a config which may contain a string or comma delimited list
     * into a List of split and trimmed strings.
     *
     * Contains special support for nested configs of the form '[configName]'
     */
    @CompileDynamic
    List<String> getStringList(String configName) {
        def rawConfig = getString(configName, ''),
            tokens = rawConfig.split(',')*.trim(),
            ret = []

        def groupPattern = /\[([\w-]+)\]/
        for (String token : tokens) {
            def matcher = (token =~ /$groupPattern/)
            if (matcher.size() == 1) {
                ret.addAll(getStringList(matcher[0][1]))
            } else {
                ret.add(token)
            }
        }

        ret
    }

    /** Update the value of an existing config. */
    @Transactional
    AppConfig setValue(String name, Object value, String lastUpdatedBy = authUsername ?: 'hoist-config-service') {
        def currConfig = AppConfig.findByName(name, [cache: true])

        if (currConfig == null) {
            throw new RuntimeException("No config found with name: [$name]")
        }

        if (currConfig.valueType == 'json' && !(value instanceof String)) value = serializePretty(value)

        currConfig.value = value as String
        currConfig.lastUpdatedBy = lastUpdatedBy

        currConfig.save(flush: true)
    }

    /**
     * Check a list of core configurations required for Hoist/application operation — ensuring
     * that these configs are present and that their valueTypes and clientVisible flags are as
     * expected. Will create missing configs with supplied default values if not found.
     *
     * Each {@link ConfigSpec} may optionally declare a `typedClass` (JSON-type configs only) that
     * extends {@link TypedConfigMap}. When present:
     *  - Server code can load the config via {@link #getObject(Class)}.
     *  - The class's property-initializer defaults are applied at read time for any key missing
     *    from the stored map.
     *  - A `WARN` is logged at startup for any key whose typed-class default differs from the
     *    BootStrap `defaultValue`, flagging drift between the two.
     *
     * @param configSpecs - List of {@link ConfigSpec} defining the required configs.
     */
    @Transactional
    void ensureRequiredConfigsCreated(List<ConfigSpec> configSpecs) {
        configSpecs = configSpecs.collect {
            it instanceof ConfigSpec ? it : new ConfigSpec(it as Map)
        }

        def currConfigs = AppConfig.list(),
            created = 0

        configSpecs.each { ConfigSpec spec ->
            def currConfig = currConfigs.find { it.name == spec.name },
                defaultVal = spec.defaultValue

            if (!currConfig) {
                if (spec.valueType == 'json') defaultVal = serializePretty(defaultVal)

                new AppConfig(
                    name: spec.name,
                    valueType: spec.valueType,
                    value: defaultVal,
                    groupName: spec.groupName,
                    clientVisible: spec.clientVisible,
                    lastUpdatedBy: 'hoist-bootstrap',
                    note: spec.note ?: ''
                ).save()

                logWarn(
                    "Required config ${spec.name} missing and created with default value",
                    'verify default is appropriate for this application'
                )
                created++
            } else {
                if (currConfig.valueType != spec.valueType) {
                    logError(
                        "Unexpected value type for required config ${spec.name}",
                        "expected ${spec.valueType} got ${currConfig.valueType}",
                        'review and fix!'
                    )
                }
                if (currConfig.clientVisible != spec.clientVisible) {
                    logError(
                        "Unexpected clientVisible for required config ${spec.name}",
                        "expected ${spec.clientVisible} got ${currConfig.clientVisible}",
                        'review and fix!'
                    )
                }
            }

            if (spec.typedClass) {
                registerTypedConfig(spec.name, spec.typedClass, spec.defaultValue as Map)
            }
        }

        logDebug("Validated presense of ${configSpecs.size()} required configs", "created ${created}")
    }

    /**
     * @deprecated Use {@link #ensureRequiredConfigsCreated(List)} with {@link ConfigSpec} instead.
     *     Targeted for removal in v42.
     */
    @Deprecated
    @Transactional
    void ensureRequiredConfigsCreated(Map<String, Map> reqConfigs) {
        logWarn('ensureRequiredConfigsCreated(Map) is deprecated — use List<ConfigSpec> instead')
        ensureRequiredConfigsCreated(
            reqConfigs.collect { name, defaults -> new ConfigSpec([name: name] + defaults) }
        )
    }

    void fireConfigChanged(AppConfig obj) {
        getTopic('xhConfigChanged').publishAsync([key: obj.name, value: obj.externalValue()])
    }


    //-------------------
    //  Implementation
    //-------------------
    /** Registered `TypedConfigMap` subclass for the given config name.  @internal  */
    Class<? extends TypedConfigMap> getTypedClass(String name) {
        configTypeByName[name]
    }

    private void registerTypedConfig(String confName, Class typedClass, Map bootstrapDefault) {
        if (!TypedConfigMap.isAssignableFrom(typedClass)) {
            throw new RuntimeException(
                "typedClass for config '$confName' must extend TypedConfigMap — got ${typedClass.name}"
            )
        }
        def asTyped = typedClass as Class<? extends TypedConfigMap>
        configTypeByName[confName] = asTyped
        nameByConfigType[asTyped] = confName
        configDriftService.checkTypedConfigDivergence(confName, asTyped, bootstrapDefault)
    }

    @ReadOnly
    private Object getInternalByName(String name, String valueType, Object notFoundValue) {
        AppConfig c = AppConfig.findByName(name, [cache: true])

        if (c == null) {
            if (notFoundValue != null) return notFoundValue
            throw new RuntimeException("No config found with name: [$name]")
        }
        if (valueType != c.valueType) {
            throw new RuntimeException("Unexpected type for config: [$name] | config is ${c.valueType} | expected ${valueType}")
        }
        return c.externalValue(decryptPassword: true, jsonAsObject: true)
    }
}
