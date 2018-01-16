/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2018 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.pref

import io.xh.hoist.json.JSONFormat
import io.xh.hoist.util.Utils

class Preference implements JSONFormat {

    static List TYPES = ['string', 'int', 'long', 'double', 'bool', 'json']

    String name
    String type = 'string'
    String defaultValue
    String notes
    Boolean local
    String lastUpdatedBy
    Date lastUpdated

    static hasMany = [userPreferences: UserPreference]

    static mapping = {
        table 'xh_preference'
        cache true
        defaultValue type: 'text'
        userPreferences cache: true
    }

    static constraints = {
        name(maxSize: 50, unique: true)
        type(maxSize: 20, inList: Preference.TYPES)
        defaultValue(validator: {String val, Preference obj -> obj.isValidForType(val) })
        notes(nullable: true, maxSize: 1200)
        lastUpdatedBy(nullable: true, maxSize: 50)
    }

    Object isValidForType(String val) {
        if (type == 'bool' && !(val.equals('true') || val.equals('false'))) {
            return 'default.invalid.boolean.message'
        }
        if (type == 'int' && !val.isInteger()) {
            return 'default.invalid.integer.message'
        }
        if (type == 'long' && !val.isLong()) {
            return 'default.invalid.long.message'
        }
        if (type == 'double' && !val.isDouble()) {
            return 'default.invalid.double.message'
        }
        if (type == 'json' && !Utils.isJSON(val)) {
            return 'default.invalid.json.message'
        }

        return true
    }

    Map formatForJSON() {
        return [
                id: id,
                name: name,
                type: type,
                defaultValue: defaultValue,
                notes: notes,
                local: local,
                lastUpdatedBy: lastUpdatedBy,
                lastUpdated: lastUpdated
        ]
    }

}
