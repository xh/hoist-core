/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2018 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.pref

import grails.compiler.GrailsCompileStatic
import io.xh.hoist.BaseService
import io.xh.hoist.json.JSON
import org.grails.web.json.JSONElement

/**
 * Manage a given user's preferences, with typed getters & setters.
 * Client can specify a username; if none given, defaults to the current user
 */
@GrailsCompileStatic
class PrefService extends BaseService {

    String getString(String key, String username=username) {
        return (String) getUserPreference(key, 'string', username)
    }

    Integer getInt(String key, String username=username) {
        return (Integer) getUserPreference(key, 'int', username)
    }

    Long getLong(String key, String username=username) {
        return (Long) getUserPreference(key, 'long', username)
    }

    Double getDouble(String key, String username=username) {
        return (Double) getUserPreference(key, 'double', username)
    }

    Boolean getBool(String key, String username=username) {
        return (Boolean) getUserPreference(key, 'bool', username)
    }

    JSONElement getJSON(String key, String username=username) {
        return (JSONElement) getUserPreference(key, 'json', username)
    }

    void setString(String key, String value, String username=username) {
        setUserPreference(key, value, 'string', username)
    }

    void setInt(String key, Integer value, String username=username) {
        setUserPreference(key, value.toString(), 'int', username)
    }

    void setLong(String key, Long value, String username=username) {
        setUserPreference(key, value.toString(), 'long', username)
    }

    void setDouble(String key, Double value, String username=username) {
        setUserPreference(key, value.toString(), 'double', username)
    }

    void setBool(String key, Boolean value, String username=username) {
        setUserPreference(key, value.toString(), 'bool', username)
    }

    void setJSON(String key, Object value, String username=username) {
        setUserPreference(key, new JSON(value).toString(), 'json', username)
    }

    void setPreference(String key, String value, String username=username) {
        setUserPreference(key, value, null, username)
    }

    void unsetPreference(String key, String username=username) {
        def defaultPref = Preference.findByName(key)
        UserPreference.findByPreferenceAndUsername(defaultPref, username)?.delete(flush: true)
    }

    void clearPreferences(String username=username) {
        UserPreference.findAllByUsername(username).each {
            UserPreference userPref = (UserPreference) it
            userPref.delete()
        }
    }

    Map getClientConfig() {
        def username = username,
            ret = [:]

        Preference.list().each {
             def name = it.name
             try {
                 ret[name] = formatForClient(it, username)
             } catch (Exception e){
                 log.error("Exception while getting client preference: '$name'", e)
             }
        }

        return ret
    }

    Map getLimitedClientConfig(List keys) {
        def username = username
        Preference.findAllByNameInList(keys).collectEntries {
            Preference pref = (Preference) it
            [pref.name, formatForClient(pref, username)]
        }
    }


    //-------------------------
    // Implementation
    //-------------------------
    private Object getUserPreference(String key, String type, String username) {
        def defaultPref = getDefaultPreference(key, type)
        return getUserPreference(defaultPref, username)
    }

    private Object getUserPreference(Preference defaultPref, String username) {
        if (defaultPref.local) {
            throw new RuntimeException("Preference ${defaultPref.name} marked as local - user value cannot be read on server.")
        }

        def userPref = UserPreference.findByPreferenceAndUsername(defaultPref, username, [cache: true]),
            value = userPref ? userPref.userValue : defaultPref.defaultValue

        return convertValue(defaultPref.type, value)
    }

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
            ret.defaultValue = convertValue(defaultPref.type, defaultPref.defaultValue)
        } else {
            // Server-side prefs serialized with merged user/default value
            ret.value = getUserPreference(defaultPref, username)
            ret.defaultValue = defaultPref.defaultValue
        }

        return ret
    }

    private Object convertValue(String type, String value) {
        switch (type)  {
            case 'json':    return JSON.parse(value)
            case 'int':     return value.toInteger()
            case 'long':    return value.toLong()
            case 'double':  return value.toDouble()
            case 'bool':    return value.toBoolean()
            default:        return value
        }
    }

}
