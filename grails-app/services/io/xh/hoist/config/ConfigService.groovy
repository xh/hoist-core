/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.config

import grails.compiler.GrailsCompileStatic
import grails.gorm.transactions.ReadOnly
import grails.gorm.transactions.Transactional
import groovy.transform.CompileDynamic
import io.xh.hoist.BaseService

import static io.xh.hoist.json.JSONSerializer.serializePretty

/**
 * Service to return soft-configured variables.
 * Fires a xhConfigChanged event when a config value is updated.
 */
@GrailsCompileStatic
class ConfigService extends BaseService {

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
     * Return a map of all config values needed by client.
     * All passwords will be obscured.
     */
    @ReadOnly
    Map getClientConfig() {
        def ret = [:]

        AppConfig.findAllByClientVisible(true, [cache: true]).each {
            AppConfig config = (AppConfig) it
            def name = config.name
            try {
                ret[name] = config.externalValue(obscurePassword: true, jsonAsObject: true)
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
    Map getForAdminStats(List<String> names) {
        AppConfig.findAllByNameInList(names, [cache: true]).collectEntries {
            AppConfig config  = it as AppConfig
            return [config.name, config.externalValue(obscurePassword: true, jsonAsObject: true)]
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
     * supplied default values if not found. Called for xh.io configs by Hoist Core Bootstrap.
     * @param reqConfigs - map of configName to map of [valueType, defaultValue, clientVisible, groupName]
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
        }

        logDebug("Validated presense of ${reqConfigs.size()} required configs", "created ${created}")
    }

    void fireConfigChanged(AppConfig obj) {
        def topic = clusterService.getTopic('xhConfigChanged')
        topic.publishAsync([key: obj.name, value: obj.externalValue()])
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
