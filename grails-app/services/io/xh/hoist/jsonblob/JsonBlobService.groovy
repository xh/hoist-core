/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2020 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.jsonblob

import io.xh.hoist.BaseService
import io.xh.hoist.json.JSONParser

class JsonBlobService extends BaseService {

    Map get(int id) {
        return formatForClient(JsonBlob.get(id), true)
    }

    List<Map> list(String type, Boolean includeValue, String username = username) {
        return JsonBlob.findAllByTypeAndUsername(type, username).collect {blob ->
            return formatForClient(blob, includeValue)
        }
    }

    Map create(String type, String name, String value, String description, String username = username) {
        JsonBlob blob = new JsonBlob(
            type: type,
            name: name,
            value: value,
            description: description,
            username: username,
            lastUpdatedBy: username,
            valueLastUpdated: new Date()
        ).save()
        return formatForClient(blob, true)
    }

    Map update(int id, String name, String value, String description) {
        JsonBlob blob = JsonBlob.get(id)

        if (name) blob.name = name
        if (description) blob.description = description
        if (value) {
            blob.value = value
            blob.valueLastUpdated = new Date()
        }

        blob.lastUpdatedBy = username
        blob.save()
        return formatForClient(blob, true)
    }

    void delete(int id) {
        JsonBlob blob = JsonBlob.get(id)
        blob.delete()
    }

    //-------------------------
    // Implementation
    //-------------------------
    private Map formatForClient(JsonBlob blob, Boolean includeValue) {
        if (!blob) return null

        def ret = [
            id: blob.id,
            type: blob.type,
            username: blob.username,
            name: blob.name,
            description: blob.description,
            dateCreated: blob.dateCreated,
            lastUpdated: blob.lastUpdated,
            valueLastUpdated: blob.valueLastUpdated,
            lastUpdatedBy: blob.lastUpdatedBy
        ]

        if (includeValue) {
            ret.put('value', JSONParser.parseObjectOrArray(blob.value))
        }

        return ret
    }

}