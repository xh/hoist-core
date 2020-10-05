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

    Map get(String token) {
        JsonBlob blob = JsonBlob.findByTokenAndArchived(token, false)
        ensurePassesAcl(blob)
        return formatForClient(blob, true)
    }

    List<Map> list(String type, Boolean includeValue) {
        return JsonBlob.findAllByTypeAndArchived(type, false).findAll {blob ->
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

    Map update(String token, String name, String value, String description) {
        JsonBlob blob = JsonBlob.findByTokenAndArchived(token, false)
        ensurePassesAcl(blob)

        if (name) blob.name = name
        if (value) blob.value = value
        if (description) blob.description = description

        blob.lastUpdatedBy = username
        blob.save()
        return formatForClient(blob, true)
    }

    void archive(String token) {
        JsonBlob blob = JsonBlob.findByTokenAndArchived(token, false)
        ensurePassesAcl(blob)
        blob.archived = true
        blob.save()
    }

    //-------------------------
    // Implementation
    //-------------------------
    private boolean passesAcl(JsonBlob blob) {
        return blob.acl == '*' || blob.owner == username
    }

    private ensurePassesAcl(JsonBlob blob) {
        if (blob && !passesAcl(blob)) {
            throw new NotAuthorizedException("'${username}' does not have access to the JsonBlob.")
        }
    }

    private Map formatForClient(JsonBlob blob, Boolean includeValue) {
        if (!blob) return null

        def ret = [
            id: blob.id,
            token: blob.token,
            type: blob.type,
            owner: blob.owner,
            acl: blob.acl,
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