/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2021 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.jsonblob

import grails.gorm.transactions.ReadOnly
import grails.gorm.transactions.Transactional
import grails.web.databinding.DataBinder
import io.xh.hoist.BaseService
import io.xh.hoist.exception.NotAuthorizedException

import static io.xh.hoist.json.JSONSerializer.serialize
import static java.lang.System.currentTimeMillis

class JsonBlobService extends BaseService implements DataBinder {

    @ReadOnly
    JsonBlob get(String token, String username = username) {
        JsonBlob ret = JsonBlob.findByTokenAndArchivedDate(token, 0)
        if (!ret) {
            throw new RuntimeException("Active JsonBlob not found with token '$token'")
        }
        if (!passesAcl(ret, username)) {
            throw new NotAuthorizedException("User '$username' does not have access to JsonBlob with token '$token'")
        }
        return ret
    }

    @ReadOnly
    List<JsonBlob> list(String type, String username = username) {
        JsonBlob
                .findAllByTypeAndArchivedDate(type, 0)
                .findAll { passesAcl(it, username) }
    }

    @Transactional
    JsonBlob create(Map data, String username = username) {
        data = [*: data, owner: username, lastUpdatedBy: username]
        if (data.containsKey('value')) data.value = serialize(data.value)
        if (data.containsKey('meta')) data.meta = serialize(data.meta)

        new JsonBlob(data).save()
    }

    @Transactional
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

    @Transactional
    JsonBlob archive(String token, String username = username) {
        def blob = get(token, username)
        blob.archivedDate = currentTimeMillis()

        blob.lastUpdatedBy = authUsername
        blob.save()
    }


    //-------------------------
    // Implementation
    //-------------------------
    private boolean passesAcl(JsonBlob blob, String username) {
        return blob.acl == '*' || blob.owner == username
    }
}
