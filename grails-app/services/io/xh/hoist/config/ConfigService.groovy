/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2018 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.config

import grails.compiler.GrailsCompileStatic
import grails.events.annotation.Subscriber
import groovy.transform.CompileDynamic
import io.xh.hoist.BaseService
import io.xh.hoist.util.Utils
import io.xh.hoist.json.JSON
import org.grails.datastore.mapping.engine.event.PreUpdateEvent
import org.grails.web.json.JSONArray
import org.grails.web.json.JSONElement
import org.grails.web.json.JSONObject
import grails.events.*

import static io.xh.hoist.AppEnvironment.BETA
import static io.xh.hoist.AppEnvironment.DEVELOPMENT
import static io.xh.hoist.AppEnvironment.PRODUCTION
import static io.xh.hoist.AppEnvironment.STAGING


/**
 * Service to return soft-configured variables based on the current environment.
 *
 * Values for config keys can be entered and adjusted for all supported environments -
 * Production, Beta, Staging, Development.  A production value is always required.
 * Other values are optional - if not specified, they will fall back to production.
 *
 * Fires a xhConfigChanged event when a config value is updated for the current environment.
 */
@GrailsCompileStatic
class ConfigService extends BaseService implements EventPublisher {

    String getString(String name, String notFoundValue=null) {
        return (String) getInternalByName(name, 'string', notFoundValue)
    }

    Integer getInt(String name, Integer notFoundValue=null) {
        return (Integer) getInternalByName(name, 'int', notFoundValue)
    }

    Long getLong(String name, Integer notFoundValue=null) {
        return (Long) getInternalByName(name, 'long', notFoundValue)
    }

    Double getDouble(String name, Integer notFoundValue=null) {
        return (Double) getInternalByName(name, 'double', notFoundValue)
    }

    Boolean getBool(String name, Boolean notFoundValue=null) {
        return (Boolean) getInternalByName(name, 'bool', notFoundValue)
    }

    JSONElement getJSON(String name, JSONElement notFoundValue=null) {
        return (JSONElement) getInternalByName(name, 'json', notFoundValue)
    }

    JSONObject getJSONObject(String name, JSONObject notFoundValue=null) {
        return (JSONObject) getJSON(name, notFoundValue)
    }

    JSONArray getJSONArray(String name, JSONArray notFoundValue=null) {
        return (JSONArray) getJSON(name, notFoundValue)
    }

    String getPwd(String name, String notFoundValue=null) {
        return (String) getInternalByName(name, 'pwd', notFoundValue)
    }

    Map getClientConfig() {
        def ret = [:]

        AppConfig.findAllByClientVisible(true, [cache: true]).each {
            AppConfig config = (AppConfig) it
            def name = config.name
            try {
                def configVal = getInternal(config, null),
                    isPwd = config.valueType == 'pwd'
                ret[name] = isPwd ? '***********' : configVal
            } catch (Exception e) {
                log.error("Exception while getting client config: '$name'", e)
            }
        }

        return ret
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

    /**
     * Check a list of core configurations required for Hoist/application operation - ensuring that these configs are
     * present and that their valueTypes and clientVisible flags are are as expected. Will create missing configs with
     * supplied default values if not found. Called for xh.io configs by Hoist Core Bootstrap.
     * @param reqConfigs - map of configName to map of [valueType, defaultValue, clientVisible, groupName]
     */
    void ensureRequiredConfigsCreated(Map<String, Map> reqConfigs) {
        def currConfigs = AppConfig.list(),
            created = 0

        reqConfigs.each{confName, confDefaults ->
            def currConfig = currConfigs.find{it.name == confName},
                valType = confDefaults.valueType,
                defaultVal = confDefaults.defaultValue,
                clientVisible = confDefaults.clientVisible ?: false

            if (!currConfig) {
                if (valType == 'json') defaultVal = new JSON(defaultVal).toString()

                new AppConfig(
                    name: confName,
                    valueType: valType,
                    prodValue: defaultVal,
                    groupName: confDefaults.groupName ?: 'xh.io',
                    clientVisible: clientVisible,
                    lastUpdatedBy: 'hoist-bootstrap'
                ).save()

                log.warn("Required config ${confName} missing and created with default value | verify default is appropriate for this application")
                created++
            } else {
                if (currConfig.valueType != valType) {
                    log.error("Unexpected value type for required config ${confName} | expected ${valType} got ${currConfig.valueType} | review and fix!")
                }
                if (currConfig.clientVisible != clientVisible) {
                    log.error("Unexpected clientVisible for required config ${confName} | expected ${clientVisible} got ${currConfig.clientVisible} | review and fix!")
                }
            }
        }

        log.debug("Validated presense of ${reqConfigs.size()} required configs | created ${created}")
    }


    //-------------------
    //  Implementation
    //-------------------
    private Object getInternalByName(String name, String valueType, Object notFoundValue) {
        AppConfig c = AppConfig.findByName(name, [cache: true])

        if (c == null) {
            if (notFoundValue != null) return notFoundValue
            throw new RuntimeException('No key for configuration found: ' + name)
        }
        if (valueType != c.valueType) {
            throw new RuntimeException('Unexpected type for key: ' + name)
        }
        return getInternal(c, notFoundValue)
    }


    private Object getInternal(AppConfig config, Object notFoundValue) {
        def val = null

        switch (Utils.getAppEnvironment()) {
            case DEVELOPMENT:   val = config.devValue; break
            case STAGING:       val = config.stageValue; break
            case BETA:          val = config.betaValue; break
            case PRODUCTION:    val = config.prodValue; break
        }

        String ret = (val != null ? val : config.prodValue)

        if (ret == null) return notFoundValue

        switch(config.valueType) {
            case 'json':    return JSON.parse(ret)
            case 'int':     return ret.toInteger()
            case 'long':    return ret.toLong()
            case 'double':  return ret.toDouble()
            case 'bool':    return ret.toBoolean()
            case 'pwd':     return AppConfig.decryptPassword(ret)
            case 'string' : return ret
            default:        return ret
        }
    }

    //------------------------------------------------------------------------------
    // Listen to Changes to AppConfig object to generate 'xhConfigChanged'
    //
    // Note:  Use beforeUpdate instead of afterUpdate, because easier to identify
    // what changed (rare extra event for aborted updates preferable to complexity)
    //------------------------------------------------------------------------------
    @Subscriber
    void beforeUpdate(PreUpdateEvent event) {
        if (!(event.entityObject instanceof AppConfig)) return

        def obj = (AppConfig) event.entityObject,
            devChanged = obj.hasChanged('devValue'),
            stageChanged = obj.hasChanged('stageValue'),
            betaChanged = obj.hasChanged('betaValue'),
            prodChanged = obj.hasChanged('prodValue'),
            env = Utils.getAppEnvironment(),
            newVal = getInternal(obj, null),
            materialChanges = (
                    (env == PRODUCTION && prodChanged) ||
                    (env == BETA && (betaChanged || (obj.betaValue == null && prodChanged))) ||
                    (env == STAGING && (stageChanged || (obj.stageValue == null && prodChanged))) ||
                    (env == DEVELOPMENT && (devChanged || (obj.devValue == null && prodChanged)))
            )

        if (materialChanges) {
            // notify is called in a new thread and with a delay to make sure the newVal has had the time to propagate
            asyncTask {
                Thread.sleep(500)
                notify('xhConfigChanged', [key: obj.name, value: newVal])
            }
        }
    }
    
}
