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

    // Registry of typed config classes, keyed by backing config name. Populated when apps declare
    // a `typedClass:` entry in `ensureRequiredConfigsCreated`. Used to (a) validate name/class
    // alignment at startup, (b) populate `getClientConfig()` payloads with typed-class defaults,
    // and (c) flag drift between declared property defaults and BootStrap `defaultValue`.
    private final Map<String, Class<? extends TypedConfigMap>> typedConfigs = [:]

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
     * The supplied class must extend {@link TypedConfigMap} and declare the backing config
     * name via `getConfigName()`. This is the preferred way to read structured configs —
     * it centralizes defaults and documentation on the typed class itself, rather than
     * scattering `?:` fallbacks across call sites.
     */
    <T extends TypedConfigMap> T getTypedConfig(Class<T> clazz) {
        // Resolve config name: prefer registry (avoids constructing twice), fall back to an
        // empty instance to read the name if the class hasn't been registered via BootStrap.
        String name = typedConfigs.find { it.value == clazz }?.key
            ?: ((TypedConfigMap) clazz.getDeclaredConstructor(Map).newInstance([:])).configName
        if (!name) {
            throw new RuntimeException(
                "${clazz.simpleName} must declare getConfigName() to be loadable via getTypedConfig()"
            )
        }
        // Construct via Map constructor — subclass is expected to call `init(args)` in its
        // constructor body, where declared field initializers have already run. (If init ran
        // via `super(args)`, field initializers would clobber the applied values.)
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
                def typedClass = typedConfigs[name]
                if (typedClass && config.valueType == 'json') {
                    // Typed instance implements JSONFormat — Jackson auto-serializes via
                    // formatForJSON(), so declared property defaults reach the client even for
                    // keys missing from the stored map.
                    ret[name] = getTypedConfig(typedClass)
                } else {
                    ret[name] = config.externalValue(obscurePassword: true, jsonAsObject: true)
                }
            } catch (Exception e) {
                logError("Exception while getting client config: '$name'", e)
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
     * Check a list of core configurations required for Hoist/application operation - ensuring that these configs are
     * present and that their valueTypes and clientVisible flags are are as expected. Will create missing configs with
     * supplied default values if not found.
     *
     * Supported keys per entry:
     *  - `valueType` (required) — one of `string|int|long|double|bool|json|pwd`
     *  - `defaultValue` — seed value written to the DB when the config row is first created
     *  - `clientVisible` — true to include in `getClientConfig()` payloads
     *  - `groupName`, `note` — metadata shown in the Admin Console
     *  - `typedClass` (optional, JSON-type configs only) — a concrete {@link TypedConfigMap}
     *    subclass whose `getConfigName()` matches the entry's key. When present:
     *      + Server code can load the config via {@link #getTypedConfig(Class)}.
     *      + The class's property-initializer defaults are applied at read time for any
     *        key missing from the stored map — centralizing defaults next to the type.
     *      + For `clientVisible` configs, the payload sent to the client is populated
     *        through the typed class, so declared defaults reach client code as well.
     *      + A `WARN` is logged at startup for any key whose typed-class default differs
     *        from the BootStrap `defaultValue`, flagging drift between the two.
     *    `typedClass` is fully optional — entries without it retain the prior behavior
     *    (raw map served to clients, inline fallbacks at call sites).
     *
     * @param reqConfigs - map of configName to entry-config map as described above
     */
    @Transactional
    void ensureRequiredConfigsCreated(Map<String, Map> reqConfigs) {
        def currConfigs = AppConfig.list(),
            created = 0

        reqConfigs.each { confName, confDefaults ->
            def currConfig = currConfigs.find { it.name == confName },
                valType = confDefaults.valueType,
                defaultVal = confDefaults.defaultValue,
                clientVisible = confDefaults.clientVisible ?: false,
                note = confDefaults.note ?: ''

            if (!currConfig) {

                if (valType == 'json') defaultVal = serializePretty(defaultVal)

                new AppConfig(
                    name: confName,
                    valueType: valType,
                    value: defaultVal,
                    groupName: confDefaults.groupName ?: 'Default',
                    clientVisible: clientVisible,
                    lastUpdatedBy: 'hoist-bootstrap',
                    note: note
                ).save()

                logWarn(
                    "Required config $confName missing and created with default value",
                    'verify default is appropriate for this application'
                )
                created++
            } else {
                if (currConfig.valueType != valType) {
                    logError(
                        "Unexpected value type for required config $confName",
                        "expected $valType got ${currConfig.valueType}",
                        'review and fix!'
                    )
                }
                if (currConfig.clientVisible != clientVisible) {
                    logError(
                        "Unexpected clientVisible for required config $confName",
                        "expected $clientVisible got ${currConfig.clientVisible}",
                        'review and fix!'
                    )
                }
            }

            if (confDefaults.typedClass) {
                registerTypedConfig(confName, confDefaults.typedClass as Class, confDefaults.defaultValue as Map)
            }
        }

        logDebug("Validated presense of ${reqConfigs.size()} required configs", "created ${created}")
    }

    //-------------------
    //  Typed Config Registration
    //-------------------
    private void registerTypedConfig(String confName, Class typedClass, Map bootstrapDefault) {
        if (!TypedConfigMap.isAssignableFrom(typedClass)) {
            logError("typedClass for config '$confName' must extend TypedConfigMap", "got: ${typedClass.name}")
            return
        }
        TypedConfigMap sample
        try {
            // Map ctor with empty arg: subclass calls init([:]) in body, which no-ops
            // and leaves declared field defaults in place.
            sample = (TypedConfigMap) typedClass.getDeclaredConstructor(Map).newInstance([:])
        } catch (Exception e) {
            logError("Unable to instantiate typedClass ${typedClass.simpleName} for config '$confName'", e.message)
            return
        }

        def declaredName = sample.configName
        if (declaredName != confName) {
            logError(
                "typedClass ${typedClass.simpleName}.configName ('$declaredName') does not match registered config name",
                "expected: '$confName'",
                'review and fix!'
            )
            return
        }

        typedConfigs[confName] = typedClass as Class<? extends TypedConfigMap>
        checkTypedConfigDivergence(confName, sample, bootstrapDefault)
    }

    private void checkTypedConfigDivergence(String confName, TypedConfigMap sample, Map bootstrapDefault) {
        if (!bootstrapDefault) return

        def divergences = collectDivergences(sample.formatForJSON(), bootstrapDefault, sample.getClass().simpleName)
        if (divergences) {
            logWarn(
                "Typed config defaults diverge from BootStrap for '$confName'",
                divergences.join(' | '),
                'align BootStrap defaultValue with property initializers on ' + sample.getClass().simpleName
            )
        }
    }

    // Recursively compare a typed-class's declared defaults (a Map that may contain nested
    // TypedConfigMap instances) against a BootStrap defaultValue Map. Produces a flat list of
    // divergence messages.
    private List<String> collectDivergences(Map typedDefaults, Map bootstrapDefault, String typedClassName, String pathPrefix = '') {
        List<String> divergences = []
        typedDefaults.each { k, tv ->
            if (!bootstrapDefault.containsKey(k)) return
            def bv = bootstrapDefault[k]
            String path = pathPrefix ? "${pathPrefix}.${k}" : "${k}"

            // Typed submap vs. Map in bootstrap → recurse. Empty bootstrap map is the
            // accepted convention for "typed class fully owns the nested shape".
            if (tv instanceof TypedConfigMap && bv instanceof Map) {
                if (bv) divergences.addAll(collectDivergences(tv.formatForJSON(), bv, typedClassName, path))
                return
            }
            // List containing TypedConfigMap vs. List in bootstrap → compare element-wise.
            // Needed because TypedConfigMap instances don't override equals, so a direct List
            // comparison would always report divergence for the same-shape typed list.
            if (tv instanceof List && bv instanceof List && tv.any { it instanceof TypedConfigMap }) {
                List tvList = tv, bvList = bv
                if (tvList.size() != bvList.size()) {
                    divergences << "'$path': typedClass has ${tvList.size()} element(s), bootstrap has ${bvList.size()}".toString()
                } else {
                    tvList.eachWithIndex { tvElem, i ->
                        def bvElem = bvList[i]
                        String elemPath = "${path}[${i}]"
                        if (tvElem instanceof TypedConfigMap && bvElem instanceof Map) {
                            divergences.addAll(collectDivergences(tvElem.formatForJSON(), bvElem, typedClassName, elemPath))
                        } else if (tvElem != bvElem) {
                            divergences << "'$elemPath': typedClass=${tvElem} bootstrap=${bvElem}".toString()
                        }
                    }
                }
                return
            }
            if (tv != bv) divergences << "'$path': typedClass=${tv} bootstrap=${bv}".toString()
        }
        bootstrapDefault.keySet().findAll { !typedDefaults.containsKey(it) }.each {
            String path = pathPrefix ? "${pathPrefix}.${it}" : "${it}"
            divergences << "'$path': declared in BootStrap defaultValue but not on $typedClassName".toString()
        }
        return divergences
    }

    void fireConfigChanged(AppConfig obj) {
        getTopic('xhConfigChanged').publishAsync([key: obj.name, value: obj.externalValue()])
    }

    //-------------------
    //  Implementation
    //-------------------
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
