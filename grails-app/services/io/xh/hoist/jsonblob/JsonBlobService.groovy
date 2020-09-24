/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2020 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.jsonblob

import io.xh.hoist.BaseService
import io.xh.hoist.exception.NotAuthorizedException
import io.xh.hoist.json.JSONParser

class JsonBlobService extends BaseService {

    Map get(int id) {
        return formatForClient(JsonBlob.get(id), true)
    }

    List<Map> list(String type, Boolean includeValue) {
        return JsonBlob.findAllByType(type).findAll {blob ->
            return passesAcl(blob)
        }.collect {blob ->
            return formatForClient(blob, includeValue)
        }
    }

    Map create(String type, String name, String value, String description) {
        JsonBlob blob = new JsonBlob(
            type: type,
            name: name,
            value: value,
            description: description,
            owner: username,
            lastUpdatedBy: username
        ).save()
        return formatForClient(blob, true)
    }

    Map update(int id, String name, String value, String description) {
        JsonBlob blob = JsonBlob.get(id)
        ensurePassesAcl(blob)

        if (name) blob.name = name
        if (value) blob.value = value
        if (description) blob.description = description

        blob.lastUpdatedBy = username
        blob.save()
        return formatForClient(blob, true)
    }

    void delete(int id) {
        JsonBlob blob = JsonBlob.get(id)
        ensurePassesAcl(blob)
        blob.delete()
    }

    //-------------------------
    // Implementation
    //-------------------------
    private boolean passesAcl(JsonBlob blob) {
        return blob.acl == '*' || blob.owner == username
    }

    private ensurePassesAcl(JsonBlob blob) {
        if (!passesAcl(blob)) {
            throw new NotAuthorizedException("'${username}' does not have access to the JsonBlob.")
        }
    }

    private Map formatForClient(JsonBlob blob, Boolean includeValue) {
        if (!blob) return null

        def ret = [
            id: blob.id,
            type: blob.type,
            owner: blob.owner,
            name: blob.name,
            description: blob.description,
            dateCreated: blob.dateCreated,
            lastUpdated: blob.lastUpdated,
            lastUpdatedBy: blob.lastUpdatedBy
        ]

        if (includeValue) {
            ret.put('value', JSONParser.parseObjectOrArray(blob.value))
        }

        return ret
    }

}