/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2021 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.jsonblob

import io.xh.hoist.json.JSONFormat
import io.xh.hoist.util.Utils

class JsonBlob implements JSONFormat {

    String token = UUID.randomUUID().toString().substring(0, 8)
    String type
    String owner
    String acl
    String name
    String value
    String meta
    String description
    Date dateCreated
    Date lastUpdated
    String lastUpdatedBy
    long archivedDate = 0

    static mapping = {
        table 'xh_json_blob'
        cache true
        token index: 'idx_xh_json_blob_token'
        acl type: 'text'
        value type: 'text'
        meta type: 'text'
        description type: 'text'
    }

    static constraints = {
        token unique: true, blank: false
        type maxSize: 50, blank: false
        owner maxSize: 50, nullable: true, blank: false
        acl nullable: true
        name maxSize: 255, blank: false, unique: ['owner', 'type', 'archivedDate']
        value validator: {Utils.isJSON(it) ?: 'default.invalid.json.message'}
        meta nullable: true, validator: {Utils.isJSON(it) ?: 'default.invalid.json.message'}
        description nullable: true
        lastUpdatedBy nullable: true
    }

    Map formatForJSON() {[
        id: id,
        token: token,
        type: type,
        owner: owner,
        acl: acl,
        name: name,
        value: value,
        meta: meta,
        description: description,
        archived: archivedDate > 0,
        archivedDate: archivedDate,
        dateCreated: dateCreated,
        lastUpdated: lastUpdated,
        lastUpdatedBy: lastUpdatedBy
    ]}
}
