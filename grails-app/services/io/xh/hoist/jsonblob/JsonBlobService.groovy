/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2021 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.jsonblob

import grails.web.databinding.DataBinder
import io.xh.hoist.BaseService
import io.xh.hoist.exception.NotAuthorizedException

import static io.xh.hoist.json.JSONSerializer.serialize
import static java.lang.System.currentTimeMillis

class JsonBlobService extends BaseService implements DataBinder {

    JsonBlob get(String token, String username = username) {
        JsonBlob ret = JsonBlob.findByTokenAndArchivedDate(token, 0)
        if (!ret) {
            throw new RuntimeException("Active JsonBlob not found: '$token'")
        }
        if (!passesAcl(ret, username)) {
            throw new NotAuthorizedException("'$username' does not have access to the JsonBlob.")
        }
        return ret
    }

    List<JsonBlob> list(String type, String username = username) {
        JsonBlob
                .findAllByTypeAndArchivedDate(type, 0)
                .findAll { passesAcl(it, username) }
    }

    JsonBlob create(Map data, String username = username) {
        data = [*: data, owner: username, lastUpdatedBy: username]
        if (data.containsKey('value')) data.value = serialize(data.value)
        if (data.containsKey('meta')) data.meta = serialize(data.meta)

        new JsonBlob(data).save()
    }

    JsonBlob update(String token, Map data, String username = username) {
        def blob = get(token, username)
        if (data) {
            data = [*: data, lastUpdatedBy: username]
            if (data.containsKey('value')) data.value = serialize(data.value)
            if (data.containsKey('meta')) data.meta = serialize(data.meta)

            bindData(blob, data)
            blob.save()
        }
        return blob
    }

    JsonBlob archive(String token, String username = username) {
        def blob = get(token, username)
        blob.archivedDate = currentTimeMillis()

        blob.lastUpdatedBy = username
        blob.save()
    }


    //-------------------------
    // Implementation
    //-------------------------
    private boolean passesAcl(JsonBlob blob, String username) {
        return blob.acl == '*' || blob.owner == username
    }
}
