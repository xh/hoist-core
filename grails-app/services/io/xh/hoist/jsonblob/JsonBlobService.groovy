/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2021 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.jsonblob

import io.xh.hoist.BaseService
import io.xh.hoist.exception.NotAuthorizedException
import io.xh.hoist.json.JSONParser
import static io.xh.hoist.json.JSONSerializer.serialize

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

    Map create(String type, String name, String value, String meta, String description) {
        JsonBlob blob = new JsonBlob(
            type: type,
            name: name,
            value: value,
            meta: meta,
            description: description,
            owner: username,
            lastUpdatedBy: username
        ).save()
        return formatForClient(blob, true)
    }

    Map update(String token, String payload) {
        def blob = getAvailableBlob(token),
            data = JSONParser.parseObject(payload)

        if (data.containsKey('name')) blob.name = data.name
        if (data.containsKey('value')) blob.value = serialize(data.value)
        if (data.containsKey('meta')) blob.meta = serialize(data.meta)
        if (data.containsKey('description')) blob.description = data.description

        blob.lastUpdatedBy = username
        blob.save()
        return formatForClient(blob, true)
    }

    Map archive(String token) {
        def blob = getAvailableBlob(token)
        blob.archivedDate = new Date().getTime()

        blob.lastUpdatedBy = username
        blob.save()
        return formatForClient(blob, true)
    }

    //------------------------------------------------------------------
    // Methods for protected server-side use (not exposed in client API)
    //------------------------------------------------------------------
    List<JsonBlob> listSystemBlobs(String type) {
        JsonBlob.findAllByTypeAndOwnerAndArchivedDate(type, null, 0)
    }

    JsonBlob createSystemBlob(String type, String name, String value, String meta, String description) {
        new JsonBlob(
                type: type,
                name: name,
                value: value,
                meta: meta,
                description: description,
                owner: null,
                lastUpdatedBy: username
        )
    }

    JsonBlob updateAcl(String token, String acl) {
        def blob = getAvailableBlob(token)
        blob.acl = acl

        blob.lastUpdatedBy = username
        blob.save()
        return blob
    }

    JsonBlob updateOwner(String token, String owner) {
        def blob = getAvailableBlob(token)
        blob.owner = owner

        blob.lastUpdatedBy = username
        blob.save()
        return blob
    }

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

    //-------------------------
    // Implementation
    //-------------------------
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
            lastUpdatedBy: blob.lastUpdatedBy,
            meta: JSONParser.parseObjectOrArray(blob.meta)
        ]

        if (includeValue) {
            ret.put('value', JSONParser.parseObjectOrArray(blob.value))
        }

        return ret
    }

}
