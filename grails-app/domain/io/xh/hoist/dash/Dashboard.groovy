/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2018 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.dash

import io.xh.hoist.json.JSONFormat
import io.xh.hoist.util.Utils

class Dashboard implements JSONFormat {

    String appCode
    String name
    String username
    String definition
    Date dateCreated
    Date lastUpdated

    static mapping = {
        table 'xh_dashboard'
        cache true
        definition type: 'text'
    }

    static constraints = {
        appCode(maxSize: 50)
        name(maxSize: 50)
        username(maxSize: 50)
        definition(validator: {Utils.isJSON(it) ?: 'default.invalid.json.message'})
    }

    Map formatForJSON() {
        return [
                id: id,
                appCode: appCode,
                name: name,
                username: username,
                definition: definition,
                dateCreated: dateCreated,
                lastUpdated: lastUpdated
        ]
    }

}
