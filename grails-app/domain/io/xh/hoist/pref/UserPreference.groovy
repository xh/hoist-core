/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2018 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.pref

import io.xh.hoist.json.JSONFormat

class UserPreference implements JSONFormat {

    String username
    String userValue
    String lastUpdatedBy
    Date lastUpdated

    static belongsTo = [preference:Preference]

    static mapping = {
        table 'xh_user_preference'
        cache true
        userValue type: 'text'
    }

    static constraints = {
        username(maxSize: 50, unique: 'preference')
        userValue(validator: {String val, UserPreference obj -> obj.preference.isValidForType(val)})
        lastUpdatedBy(nullable: true, maxSize: 50)
    }

    Map formatForJSON() {
        return [
                id: id,
                name: preference.name,
                groupName: preference.groupName,
                type: preference.type,
                username: username,
                userValue: userValue,
                lastUpdatedBy: lastUpdatedBy,
                lastUpdated: lastUpdated
        ]
    }
    
}
