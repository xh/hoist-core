/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2020 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.pref

import grails.compiler.GrailsCompileStatic
import grails.gorm.transactions.Transactional
import grails.gorm.transactions.ReadOnly
import io.xh.hoist.BaseService

import static io.xh.hoist.json.JSONSerializer.serialize
import static io.xh.hoist.json.JSONSerializer.serializePretty

/**
 * Manage a given user's preferences, with typed getters & setters.
 * Client can specify a username; if none given, defaults to the current user
 */
@GrailsCompileStatic
class PrefService extends BaseService {

    String getString(String key, String username = username) {
        return (String) getUserPreference(key, 'string', username)
    }

    Integer getInt(String key, String username = username) {
        return (Integer) getUserPreference(key, 'int', username)
    }

    Long getLong(String key, String username = username) {
        return (Long) getUserPreference(key, 'long', username)
    }

    Double getDouble(String key, String username = username) {
        return (Double) getUserPreference(key, 'double', username)
    }

    Boolean getBool(String key, String username = username) {
        return (Boolean) getUserPreference(key, 'bool', username)
    }

    Map getMap(String key, String username = username) {
        return (Map) getUserPreference(key, 'json', username)
    }

    List getList(String key, String username = username) {
        return (List) getUserPreference(key, 'json', username)
    }

    void setString(String key, String value, String username = username) {
        setUserPreference(key, value, 'string', username)
    }

    void setInt(String key, Integer value, String username = username) {
        setUserPreference(key, value.toString(), 'int', username)
    }

    void setLong(String key, Long value, String username = username) {
        setUserPreference(key, value.toString(), 'long', username)
    }

    void setDouble(String key, Double value, String username = username) {
        setUserPreference(key, value.toString(), 'double', username)
    }

    void setBool(String key, Boolean value, String username = username) {
        setUserPreference(key, value.toString(), 'bool', username)
    }

    void setMap(String key, Map value, String username = username) {
        setUserPreference(key, serialize(value), 'json', username)
    }

    void setList(String key, List value, String username = username) {
        setUserPreference(key, serialize(value), 'json', username)
    }

    void setPreference(String key, String value, String username = username) {
        setUserPreference(key, value, null, username)
    }

    @Transactional
    void unsetPreference(String key, String username = username) {
        def defaultPref = Preference.findByName(key)
        UserPreference.findByPreferenceAndUsername(defaultPref, username)?.delete(flush: true)
    }

    @Transactional
    void clearPreferences(String username = username) {
        UserPreference.findAllByUsername(username).each {
            UserPreference userPref = (UserPreference) it
            userPref.delete()
        }
    }

    @ReadOnly
    Map getClientConfig() {
        def username = username,
            ret = [:]

        Preference.list().each {
            def name = it.name
            try {
                ret[name] = formatForClient(it, username)
            } catch (Exception e) {
                logErrorCompact("Exception while getting client preference: '$name'", e)
            }
        }

        return ret
    }

    @ReadOnly
    Map getLimitedClientConfig(List keys) {
        def username = username
        Preference.findAllByNameInList(keys).collectEntries {
            Preference pref = (Preference) it
            [pref.name, formatForClient(pref, username)]
        }
    }

    /**
     * Check a list of core preferences required for Hoist/application operation - ensuring that
     * these prefs are present and that their values and local flags are as expected. Will create
     * missing prefs with supplied default values if not found.
     *
     * Called for xh.io prefs by Hoist Core Bootstrap.
     *
     * @param requiredPrefs - map of prefName to map of [type, defaultValue, local, note]
     */
    @Transactional
    void ensureRequiredPrefsCreated(Map<String, Map> requiredPrefs) {
        def currPrefs = Preference.list(),
            created = 0

        requiredPrefs.each {prefName, prefDefaults ->
            def currPref = currPrefs.find {it.name == prefName},
                valType = prefDefaults.type,
                defaultVal = prefDefaults.defaultValue,
                local = prefDefaults.local ?: false,
                // Mismatch on notes <> note vs. AppConfig - stuck with singular "note" for the API to this method
                notes = prefDefaults.note ?: ''

            if (!currPref) {
                if (valType == 'json') defaultVal = serializePretty(defaultVal)

                new Preference(
                    name: prefName,
                    type: valType,
                    defaultValue: defaultVal,
                    groupName: prefDefaults.groupName ?: 'Default',
                    local: local,
                    notes: notes,
                    lastUpdatedBy: 'hoist-bootstrap'
                ).save()

                log.warn("Required preference ${prefName} missing and created with default value | verify default is appropriate for this application")
                created++
            } else {
                if (currPref.type != valType) {
                    log.error("Unexpected value type for required preference ${prefName} | expected ${valType} got ${currPref.type} | review and fix!")
                }
            }
        }

        log.debug("Validated presense of ${requiredPrefs.size()} required configs | created ${created}")
    }

    //-------------------------
    // Implementation
    //-------------------------
    private Object getUserPreference(String key, String type, String username) {
        def defaultPref = getDefaultPreference(key, type)
        return getUserPreference(defaultPref, username)
    }

    @ReadOnly
    private Object getUserPreference(Preference defaultPref, String username) {
        if (defaultPref.local) {
            throw new RuntimeException("Preference ${defaultPref.name} marked as local - user value cannot be read on server.")
        }

        def userPref = UserPreference.findByPreferenceAndUsername(defaultPref, username, [cache: true])

        return userPref ? userPref.externalUserValue(jsonAsObject: true) : defaultPref.externalDefaultValue(jsonAsObject: true)
    }

    @Transactional
    private void setUserPreference(String key, String value, String type, String username) {
        def defaultPref = getDefaultPreference(key, type)

        if (defaultPref.local) {
            throw new RuntimeException("Preference ${key} marked as local - user value cannot be set on server.")
        }

        def userPref = UserPreference.findByPreferenceAndUsername(defaultPref, username, [cache: true])

        if (!userPref) {
            userPref = new UserPreference(preference: defaultPref, username: username)
        }

        userPref.userValue = value
        userPref.lastUpdatedBy = username
        userPref.save()
    }

    @ReadOnly
    private Preference getDefaultPreference(String key, String type) {
        def p = Preference.findByName(key, [cache: true])

        if (!p) {
            throw new RuntimeException('Preference not found: ' + key)
        }
        if (type && p.type != type) {
            throw new RuntimeException('Unexpected type for preference: ' + key)
        }

        return p
    }

    private Map formatForClient(Preference defaultPref, String username) {
        def ret = [local: defaultPref.local, type: defaultPref.type] as Map<String, Object>

        if (defaultPref.local) {
            // Local prefs serialized with default value only - client checks for local user value
            ret.value = null
            ret.defaultValue = defaultPref.externalDefaultValue(jsonAsObject: true)
        } else {
            // Server-side prefs serialized with merged user/default value
            ret.value = getUserPreference(defaultPref, username)
            ret.defaultValue = defaultPref.externalDefaultValue(jsonAsObject: true)
        }

        return ret
    }
}
