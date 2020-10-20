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
        def blob = getAvailableBlob(token)
        return formatForClient(blob, true)
    }

    List<Map> list(String type, Boolean includeValue) {
        return JsonBlob
                .findAllByTypeAndArchivedDate(type, 0)
                .findAll {passesAcl(it)}
                .collect {formatForClient(it, includeValue)
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
        def blob = getAvailableBlob(token)

        if (name) blob.name = name
        if (value) blob.value = value
        if (description) blob.description = description

        blob.lastUpdatedBy = username
        blob.save()
        return formatForClient(blob, true)
    }

    Map archive(String token) {
        def blob = getAvailableBlob(token)
        blob.archivedDate = new Date().getTime()
        blob.save()
        return formatForClient(blob, true)
    }

    //-------------------------
    // Implementation
    //-------------------------
    JsonBlob getAvailableBlob(String token) {
        JsonBlob blob = JsonBlob.findByTokenAndArchivedDate(token, 0)
        if (!blob) {
            throw new RuntimeException("Active JsonBlob not found: '$token'")
        }
        if (!passesAcl(blob)) {
            throw new NotAuthorizedException("'${username}' does not have access to the JsonBlob.")
        }
        return blob
    }


    private boolean passesAcl(JsonBlob blob) {
        return blob.acl == '*' || blob.owner == username
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
            archived: blob.archivedDate > 0,
            archivedDate: blob.archivedDate,
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