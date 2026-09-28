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
import io.xh.hoist.user.HoistUser

import static io.xh.hoist.json.JSONParser.parseObject
import static io.xh.hoist.json.JSONSerializer.serialize
import static io.xh.hoist.util.Utils.getConfigService
import static io.xh.hoist.util.Utils.getUserService
import static java.lang.System.currentTimeMillis

/**
 * Service to persist and retrieve arbitrary JSON data as `JsonBlob` domain objects - suitable for
 * application state that does not warrant its own domain class. Exposed to hoist-react's
 * `XH.jsonBlobService` via `XhController`, and used within Hoist by services such as `ViewService`
 * (ViewManager views) and `AlertBannerService`.
 *
 * Blobs are identified by a generated `token` and grouped by an application-defined `type`, with a
 * `name` that must be unique for a given type and owner. Each holds a JSON `value`, plus optional
 * `meta` for application-specific metadata. `archive` soft-deletes by setting `archivedDate`, and
 * lookups here return active blobs only.
 *
 * Read access is granted per blob: to its `owner`, or to all users when its `acl` is set to the
 * wildcard `*`. Write access (update, archive, group rename) is limited to the `owner`. A null owner
 * indicates a global blob, belonging to no single user - creating, modifying, or archiving global
 * blobs requires the roles configured in `xhJsonBlobConfig.globalWriteRoles` (see
 * {@link #canWriteGlobal}).
 */
class JsonBlobService extends BaseService implements DataBinder {

    /** Fields settable via `update` - all others are managed by this service. */
    private static final List<String> UPDATABLE_FIELDS = ['name', 'description', 'value', 'meta', 'owner', 'acl']

    /**
     * Fields settable via `create`. Includes `type`, which is immutable once created - write access
     * to global blobs is granted per type, so retyping would bypass that check.
     */
    private static final List<String> CREATABLE_FIELDS = ['type', *UPDATABLE_FIELDS]

    @ReadOnly
    JsonBlob get(String token, String username = username) {
        JsonBlob ret = JsonBlob.findByTokenAndArchivedDate(token, 0)
        if (!ret) throw new RuntimeException("Active JsonBlob not found with token '$token'")
        ensureReadable(ret, username)
        return ret
    }

    @ReadOnly
    JsonBlob find(String type, String name, String owner, String username = username) {
        def ret = JsonBlob.findByTypeAndNameAndOwnerAndArchivedDate(type, name, owner, 0)
        if (ret) ensureReadable(ret, username)
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

    /** Update an active blob. */
    @Transactional
    JsonBlob update(String token, Map data, String username = username) {
        JsonBlob blob = get(token, username)
        ensureWritable(blob, username)
        return updateInternal(blob, data, username)
    }

    /**
     * Rename a group path across all active blobs of a given type within a single owner namespace,
     * rewriting `meta.group` on every blob whose group equals or falls under the `from` path.
     *
     * Group paths support nesting via forward-slash delimiters (e.g. for ViewManager), so this
     * both renames a group in place and re-parents it along with its subtree - renaming "A/B" to
     * "A/C" rewrites "A/B" -> "A/C" and "A/B/x" -> "A/C/x". All matching blobs are rewritten
     * within a single transaction.
     *
     * @param type - blob type within which to rename.
     * @param ownerName - owner whose blobs are to be renamed, or null for the global (null-owner)
     *      namespace. Groups are namespaced per owner, so the same path can exist independently
     *      for each owner and within the global namespace.
     * @param from - group path to rename. Required, and matched both exactly and as the parent of
     *      any paths nested beneath it.
     * @param to - replacement group path. Required.
     * @param username - user on whose behalf the rename is made. Must be `ownerName`, or permitted
     *      to write global blobs when `ownerName` is null. Recorded as `lastUpdatedBy` on each blob.
     * @return count of blobs whose group path was rewritten.
     */
    @Transactional
    int renameGroup(String type, String ownerName, String from, String to, String username = username) {
        from = from?.trim()
        to = to?.trim()

        if (!from || !to) {
            throw new IllegalArgumentException("Group rename requires both 'from' and 'to' group paths")
        }
        ensureWritable(type, ownerName, username)

        List<JsonBlob> candidates = JsonBlob.createCriteria().list {
            eq('type', type)
            eq('archivedDate', 0L)
            ownerName != null ? eq('owner', ownerName) : isNull('owner')
        } as List<JsonBlob>

        int count = 0
        candidates.each { JsonBlob blob ->
            Map meta
            try {
                meta = parseObject(blob.meta)
            } catch (Exception e) {
                logWarn('Skipping blob with unparsable meta in group rename', [token: blob.token], e)
                return
            }

            if (!(meta?.group instanceof String)) return

            String group = meta.group
            if (group != from && !group.startsWith(from + '/')) return

            String renamed = to + group.substring(from.length())
            if (renamed == group) return

            meta.group = renamed
            blob.meta = serialize(meta)
            blob.lastUpdatedBy = username
            blob.save()
            count++
        }
        logInfo('Renamed group', [type: type, from: from, to: to, updated: count])
        return count
    }

    @Transactional
    JsonBlob create(Map data, String username = username) {
        data = [owner: username, *: data.findAll { it.key in CREATABLE_FIELDS }, lastUpdatedBy: username]
        ensureWritable(data.type as String, data.owner as String, username)

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
        ensureWritable(blob, username)
        blob.archivedDate = currentTimeMillis()
        blob.lastUpdatedBy = authUsername
        blob.save()
    }

    /**
     * True if the user may create, modify, or archive global (null-owner) blobs of the given type.
     * Consults `xhJsonBlobConfig.globalWriteRoles`, where any entry for the type - even an empty
     * list - takes precedence over the `*` fallback. Roles are checked for the given (apparent)
     * user, so an admin impersonating another user has that user's access.
     */
    boolean canWriteGlobal(String type, String username) {
        Map<String, List<String>> roles = configService.getObject(JsonBlobConfig).globalWriteRoles
        List<String> required = roles.containsKey(type) ? roles[type] : roles['*']
        if (!required) return false
        if (required.contains('*')) return true

        HoistUser user = userService.find(username)
        return user?.hasAnyRole(required as String[])
    }


    //-------------------------
    // Implementation
    //-------------------------
    private JsonBlob updateInternal(JsonBlob blob, Map data, String username) {
        if (data) {
            data = [*: data.findAll { it.key in UPDATABLE_FIELDS }, lastUpdatedBy: username]
            if (data.containsKey('value')) data.value = serialize(data.value)
            if (data.containsKey('meta')) data.meta = serialize(data.meta)

            // Reassigning ownership is a write into the target namespace - e.g. promoting to global.
            if (data.containsKey('owner') && data.owner != blob.owner) {
                ensureWritable(blob.type, data.owner as String, username)
            }

            bindData(blob, data)
            blob.save()
        }
        return blob
    }

    //-------------------------
    // Access control
    //-------------------------
    private boolean canRead(JsonBlob blob, String username) {
        return blob.acl == '*' || blob.owner == username
    }

    private boolean canWriteOwner(String type, String owner, String username) {
        return owner == username || (owner == null && canWriteGlobal(type, username))
    }

    private ensureReadable(JsonBlob blob, String username) {
        if (!canRead(blob, username)) {
            throw new NotAuthorizedException("User '$username' does not have access to JsonBlob with token '${blob.token}'")
        }
    }

    private ensureWritable(JsonBlob blob, String username) {
        ensureWritable(blob.type, blob.owner, username)
    }

    private ensureWritable(String type, String owner, String username) {
        if (!canWriteOwner(type, owner, username)) {
            throw new NotAuthorizedException(
                owner == null ?
                    "User '$username' does not have write access to global JsonBlobs of type '$type'" :
                    "User '$username' does not have write access to JsonBlobs owned by '$owner'"
            )
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
