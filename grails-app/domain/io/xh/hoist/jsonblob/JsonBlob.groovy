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

    String token = UUID.randomUUID().toString().substring(0, 8)
    String type
    String owner
    String name
    String value
    String acl
    String description
    Date dateCreated
    Date lastUpdated
    String lastUpdatedBy

    static mapping = {
        table 'xh_json_blob'
        cache true
        token index: 'idx_xh_json_blob_token'
        value type: 'text'
        acl type: 'text'
        description type: 'text'
    }

    static constraints = {
        token unique: true, blank: false
        type maxSize: 50, blank: false
        owner maxSize: 50, nullable: true, blank: false
        name unique: ['owner', 'type'], maxSize: 255, blank: false
        value validator: {Utils.isJSON(it) ?: 'default.invalid.json.message'}
        acl nullable: true
        description nullable: true
        lastUpdatedBy nullable: true
    }

    Map formatForJSON() {[
        id: id,
        token: token,
        type: type,
        owner: owner,
        name: name,
        value: value,
        acl: acl,
        description: description,
        dateCreated: dateCreated,
        lastUpdated: lastUpdated,
        lastUpdatedBy: lastUpdatedBy
    ]}
}
