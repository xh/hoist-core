/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2020 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.jsonblob

import io.xh.hoist.json.JSONFormat
import io.xh.hoist.util.Utils

class JsonBlob implements JSONFormat {

    String type
    String owner
    String acl
    String name
    String value
    String description
    Date dateCreated
    Date lastUpdated
    String lastUpdatedBy

    static mapping = {
        table 'xh_json_blob'
        cache true
        acl type: 'text'
        value type: 'text'
        description type: 'text'
    }

    static constraints = {
        type maxSize: 50, blank: false
        owner maxSize: 50, nullable: true, blank: false
        acl nullable: true
        name maxSize: 255, blank: false
        value validator: {Utils.isJSON(it) ?: 'default.invalid.json.message'}
        description nullable: true
        lastUpdatedBy nullable: true
    }

    Map formatForJSON() {[
        id: id,
        type: type,
        owner: owner,
        acl: acl,
        name: name,
        value: value,
        description: description,
        dateCreated: dateCreated,
        lastUpdated: lastUpdated,
        lastUpdatedBy: lastUpdatedBy
    ]}
}
