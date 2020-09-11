/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2020 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.jsonblob

import io.xh.hoist.BaseService

class JsonBlobService extends BaseService {

    JsonBlob get(int id) {
        return JsonBlob.get(id)
    }

    List<JsonBlob> list(String type, String username = username) {
        return JsonBlob.findAllByTypeAndUsername(type, username)
    }

    JsonBlob create(String type, String name, String value, String description, String username = username) {
        JsonBlob blob = new JsonBlob(
            type: type,
            name: name,
            value: value,
            description: description,
            username: username,
            lastUpdatedBy: username,
            valueLastUpdated: new Date()
        ).save()
        return blob
    }

    JsonBlob update(int id, String name, String value, String description) {
        JsonBlob blob = JsonBlob.get(id)

        if (name) blob.name = name
        if (description) blob.description = description
        if (value) {
            blob.value = value
            blob.valueLastUpdated = new Date()
        }

        blob.lastUpdatedBy = username
        blob.save()
        return blob
    }

    void delete(int id) {
        JsonBlob blob = JsonBlob.get(id)
        blob.delete()
    }

}