/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.pref

import io.xh.hoist.json.JSONFormat
import io.xh.hoist.json.JSONParser

class UserPreference implements JSONFormat {

    String username
    String userValue
    String lastUpdatedBy
    Date lastUpdated

    static belongsTo = [preference: Preference]

    static mapping = {
        table 'xh_user_preference'
        cache true
        userValue type: 'text'
        username index: 'idx_xh_user_preference_username'
    }

    static constraints = {
        username(maxSize: 50, unique: 'preference')
        userValue(validator: {String val, UserPreference obj -> obj.preference.isValidForType(val)})
        lastUpdatedBy(nullable: true, maxSize: 50)
    }

    Object externalUserValue(Map opts = [:]) {
        def val = userValue;
        switch (preference.type) {
            case 'json':    return opts.jsonAsObject ? JSONParser.parseObjectOrArray(val) : val;
            case 'int':     return val.toInteger()
            case 'long':    return val.toLong()
            case 'double':  return val.toDouble()
            case 'bool':    return val.toBoolean()
            default:        return val
        }
    }

    Map formatForJSON() {
        return [
                id           : id,
                name         : preference.name,
                groupName    : preference.groupName,
                type         : preference.type,
                username     : username,
                userValue    : externalUserValue(),
                lastUpdatedBy: lastUpdatedBy,
                lastUpdated  : lastUpdated
        ]
    }
}
