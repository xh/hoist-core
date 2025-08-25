/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2025 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.jsonblob

import grails.gorm.transactions.ReadOnly
import grails.gorm.transactions.Transactional
import grails.web.databinding.DataBinder
import io.xh.hoist.BaseService
import io.xh.hoist.exception.NotAuthorizedException
import org.grails.datastore.mapping.query.api.BuildableCriteria

import static io.xh.hoist.json.JSONSerializer.serialize
import static java.lang.System.currentTimeMillis

class JsonBlobService extends BaseService implements DataBinder {

    @ReadOnly
    JsonBlob get(String token, String username = username) {
        JsonBlob ret = JsonBlob.findByTokenAndArchivedDate(token, 0)
        if (!ret) throw new RuntimeException("Active JsonBlob not found with token '$token'")
        ensureAccess(ret, username)
        return ret
    }

    @ReadOnly
    JsonBlob find(String type, String name, String owner, String username = username) {
        def ret = JsonBlob.findByTypeAndNameAndOwnerAndArchivedDate(type, name, owner, 0)
        if (ret) ensureAccess(ret, username)
        return ret
    }

    @ReadOnly
    List<JsonBlob> list(String type, String username = username) {
        JsonBlob
                .findAllByTypeAndArchivedDate(type, 0)
                .findAll { passesAcl(it, username) }
    }

    /** List all tokens for active blobs of a given type. */
    @ReadOnly
    List<String> listTokens(String type, String username = username) {
        BuildableCriteria c = JsonBlob.createCriteria()
        c {
            projections {
                property('token')
            }
            eq('type', type)
            eq('archivedDate', 0L)
            or {
                like('acl', '*')
                eq('owner', username)
            }
        }
    }

    /** Delete all blobs with a given name for an owner. */
    @Transactional
    void deleteByNameAndOwner(String name, String owner) {
        JsonBlob.deleteAll(
            JsonBlob.findAllByNameAndOwner(name, owner)
        )
    }


    @Transactional
    JsonBlob update(String token, Map data, String username = username) {
        def blob = get(token, username)
        return updateInternal(blob, data, username)
    }

    @Transactional
    JsonBlob create(Map data, String username = username) {
        data = [*: data, owner: username, lastUpdatedBy: username]
        if (data.containsKey('value')) data.value = serialize(data.value)
        if (data.containsKey('meta')) data.meta = serialize(data.meta)

        new JsonBlob(data).save()
    }

    @Transactional
    JsonBlob createOrUpdate(String type, String name, Map data, String username = username) {
        def blob = find(type, name, username, username)
        return blob ?
            updateInternal(blob, data, username) :
            create([*: data, type: type, name: name, owner: username], username)
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
    private JsonBlob updateInternal(JsonBlob blob, Map data, String username) {
        if (data) {
            data = [*: data, lastUpdatedBy: username]
            if (data.containsKey('value')) data.value = serialize(data.value)
            if (data.containsKey('meta')) data.meta = serialize(data.meta)

            bindData(blob, data)
            blob.save()
        }
        return blob
    }

    private boolean passesAcl(JsonBlob blob, String username) {
        return blob.acl == '*' || blob.owner == username
    }

    private ensureAccess(JsonBlob blob, String username) {
        if (!passesAcl(blob, username)) {
            throw new NotAuthorizedException("User '$username' does not have access to JsonBlob with token '${blob.token}'")
        }
    }
}
