/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.jsonblob

import grails.gorm.transactions.ReadOnly
import grails.gorm.transactions.Transactional
import grails.web.databinding.DataBinder
import io.xh.hoist.BaseService
import io.xh.hoist.exception.NotAuthorizedException

import static io.xh.hoist.json.JSONParser.parseObject
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

    /** List all active blobs of a given type available to a user. */
    @ReadOnly
    List<JsonBlob> list(String type, String username = username) {
        accessibleBlobs(type, username) as List<JsonBlob>
    }

    /** List tokens for active blobs of a given type available to a user.  */
    @ReadOnly
    List<String> listTokens(String type, String username = username) {
        accessibleBlobs(type, username, 'token') as List<String>
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
        data = [owner: username, *: data, lastUpdatedBy: username]

        if (data.containsKey('value')) data.value = serialize(data.value)
        if (data.containsKey('meta')) data.meta = serialize(data.meta)

        new JsonBlob(data).save()
    }

    @Transactional
    JsonBlob createOrUpdate(String type, String name, Map data, String username = username) {
        def blob = find(type, name, username, username)
        return blob ?
            updateInternal(blob, data, username) :
            create([*: data, type: type, name: name], username)
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
            Map groupRename = data.groupRename as Map
            String prevOwner = blob.owner

            data = data.findAll { it.key != 'groupRename' }
            data = [*: data, lastUpdatedBy: username]
            if (data.containsKey('value')) data.value = serialize(data.value)
            if (data.containsKey('meta')) data.meta = serialize(data.meta)

            bindData(blob, data)
            blob.save()

            if (groupRename) {
                cascadeGroupRename(blob, prevOwner, groupRename.from as String, groupRename.to as String, username)
            }
        }
        return blob
    }

    /**
     * Rewrite `meta.group` on all other active blobs of the same type and owner whose group
     * equals or falls under a renamed group path. Group paths support nesting via forward-slash
     * delimiters (e.g. for ViewManager) - renaming "A/B" to "A/C" rewrites "A/B" -> "A/C" and
     * "A/B/x" -> "A/C/x" on all matching blobs, within the caller's transaction.
     *
     * Scoped to the owner the source blob had before the triggering update, so groups remain
     * namespaced per owner (or per the global, null-owner namespace) even if the update also
     * changed the blob's owner. Rewrites only blobs the acting user could update individually
     * per their ACL.
     */
    private void cascadeGroupRename(JsonBlob source, String owner, String from, String to, String username) {
        if (!from?.trim() || !to?.trim() || from == to) return

        def candidates = JsonBlob.createCriteria().list {
            eq('type', source.type)
            eq('archivedDate', 0L)
            owner != null ? eq('owner', owner) : isNull('owner')
        } as List<JsonBlob>

        def count = 0
        candidates.each { blob ->
            if (blob.token == source.token) return
            // Rewrite only blobs the caller could update individually - the source blob's ACL
            // must not grant transitive write access to other blobs in the owner's namespace.
            if (!passesAcl(blob, username)) return
            Map meta
            try {
                meta = parseObject(blob.meta)
            } catch (Exception ignored) {
                return
            }
            def group = meta?.group
            if (!(group instanceof String)) return
            if (group == from || group.startsWith(from + '/')) {
                meta.group = to + group.substring(from.length())
                blob.meta = serialize(meta)
                blob.lastUpdatedBy = username
                blob.save()
                count++
            }
        }
        logDebug('Cascaded group rename', [type: source.type, from: from, to: to, updated: count])
    }


    private boolean passesAcl(JsonBlob blob, String username) {
        return blob.acl == '*' || blob.owner == username
    }

    private ensureAccess(JsonBlob blob, String username) {
        if (!passesAcl(blob, username)) {
            throw new NotAuthorizedException("User '$username' does not have access to JsonBlob with token '${blob.token}'")
        }
    }

    private Object accessibleBlobs(String type, String username, String projection = null) {
        JsonBlob.createCriteria().list {
            eq('type', type)
            eq('archivedDate', 0L)
            or {
                eq('owner', username)
                like('acl', '*') // Use like for sybase text col compat
            }

            if (projection) {
                projections { property(projection) }
            }
        }
    }
}
