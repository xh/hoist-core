/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2025 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.view

import groovy.transform.CompileStatic
import io.xh.hoist.BaseService
import io.xh.hoist.jsonblob.JsonBlob
import io.xh.hoist.jsonblob.JsonBlobService
import io.xh.hoist.track.TrackService

import static io.xh.hoist.json.JSONParser.parseObject

/**
 * Service to manage state for the Hoist React `ViewManager` component.
 *
 * Views are intended to store application-defined state - e.g. persisted grid or dashboard layouts.
 * They are persisted as JSONBlobs, with a `type` - e.g. "portfolioGrid" - defined by the app
 * developer to distinguish a library of views that should be loaded together into a view manager
 * for selection by the user.
 *
 * An optional `viewInstance` parameter can be used to distinguish between multiple view managers
 * used within a single app that should all display the same `type` of view, but maintain distinct
 * preferences as to the last-selected view.
 *
 * Provides read/update access to both saved views themselves as well as an additional state blob
 * to track user-specific preferences across views for the given type.
 */
@CompileStatic
class ViewService extends BaseService {

    JsonBlobService jsonBlobService
    TrackService trackService

    /** Name for system-managed sidecar blob to store user-specific state/preferences. */
    static final String STATE_BLOB_NAME = 'xhUserState';

    //----------------------------
    // ViewManager state + support
    //-----------------------------
    /** Get all accessible views (without value) + user-specific state. */
    Map getAllData(String type, String viewInstance, String username = username) {
        def blobs = jsonBlobService.list(type, username).split { it.name == STATE_BLOB_NAME }
        def (rawState, views) = [blobs[0], blobs[1]]

        return [
            state: getStateFromBlob(rawState ? rawState.first() : null, viewInstance),
            views: views*.formatForClient(false)
        ]
    }

    /**
     * Update user-specific state/preferences for this view type:
     *      - `currentView` - the last-selected view for this user, keyed by `viewInstance`
     *      - `userPinned` - map of view tokens to booleans, recording explicit pinning/unpinning of views by the user.
     *      - `autoSave` - boolean indicating whether the user has enabled auto-save for this view type.
     *
     * These user preferences are stored alongside and loaded/refreshed with views to keep them in
     * sync with the views themselves.
     */
    Map updateState(String type, String viewInstance, Map update, String username = username) {
        def currBlob = jsonBlobService.find(type, STATE_BLOB_NAME, username, username),
            currValue = parseObject(currBlob?.value),
            newValue = [
                currentView: currValue?.currentView ?: [:],
                userPinned : currValue?.userPinned ?: [:],
                autoSave   : currValue?.autoSave ?: false
            ]

        if (update.containsKey('currentView')) newValue.currentView[viewInstance] = update.currentView
        if (update.containsKey('userPinned')) (newValue.userPinned as Map).putAll(update.userPinned as Map)
        if (update.containsKey('autoSave')) newValue.autoSave = update.autoSave

        // Ensure that userPinned only contains tokens for views that exist
        if (newValue.userPinned) {
            Map userPinned = newValue.userPinned as Map
            userPinned.keySet().retainAll(jsonBlobService.listTokens(type, username))
        }

        def blob = jsonBlobService.createOrUpdate(type, STATE_BLOB_NAME, [value: newValue], username)
        return getStateFromBlob(blob, viewInstance)
    }

    Map clearAllState() {
        jsonBlobService.deleteByNameAndOwner(STATE_BLOB_NAME, username)
    }


    //---------------------------
    // Individual View management
    //----------------------------
    /** Fetch the latest version of a view. */
    Map get(String token, String username = username) {
        jsonBlobService.get(token, username).formatForClient(true)
    }

    /** Create a new view. */
    Map create(Map data, String username = username) {
        def isShared = data.isShared,
            isGlobal = data.isGlobal,
            meta = isGlobal ? [group: data.group] : [group: data.group, isShared: isShared]

        def ret = jsonBlobService.create([
                type       : data.type,
                name       : data.name,
                description: data.description,
                owner      : isGlobal ? null : username,
                acl        : isGlobal || isShared  ? '*' : null,
                meta       : meta,
                value      : data.value
            ], username)

        if (data.containsKey('isPinned')) {
            updateState(
                data.type as String,
                'default',
                [userPinned: [(ret.token): data.isPinned]],
                username
            )
        }

        trackChange('Created View', ret)
        ret.formatForClient(true)
    }

    /** Update a view's metadata */
    Map updateInfo(String token, Map data, String username = username) {
        def existing = jsonBlobService.get(token, username),
            meta = parseObject(existing.meta) ?: [:],
            core = [:]

        data.each { k, v ->
            if (['name', 'description'].contains(k)) {
                core[k] = v
            } else if (['group'].contains(k)) {
                meta[k] = v
            }
        }

        if (data.containsKey('isGlobal') || data.containsKey('isShared')) {
            if (data.isGlobal) {
                meta = meta.findAll { it.key != 'isShared' }
                core.owner = null
                core.acl = '*'
            } else if (data.isShared) {
                meta.isShared = data.isShared
                core.owner = username
                core.acl = '*'
            } else {
                meta.isShared = false
                core.owner = existing.owner ?: username
                core.acl = null
            }
        }

        def ret = jsonBlobService.update(token, [*: core, meta: meta], username)

        if (data.containsKey('isPinned')) {
            updateState(
                data.type as String,
                'default',
                [userPinned: [(ret.token): data.isPinned]],
                username
            )
        }


        trackChange('Updated View Info', ret)
        ret.formatForClient(true)
    }

    /** Update a view's value */
    Map updateValue(String token, Map value, String username = username) {
        def ret = jsonBlobService.update(token, [value: value], username);
        if (ret.owner == null) {
            trackChange('Updated Global View definition', ret);
        }
        ret.formatForClient(true)
    }

    /** Bulk Delete views */
    void delete(List<String> tokens, String username = username) {
        List<Exception> failures = []
        tokens.each {
            try {
                jsonBlobService.archive(it, username)
            } catch (Exception e) {
                failures << e
                logError('Failed to delete View', [token: it], e)
            }
        }
        def successCount = tokens.size() - failures.size()
        if (successCount) {
            trackChange('Deleted Views', [count: successCount])
        }

        if (failures) {
            throw new RuntimeException("Failed to delete ${failures.size()} view(s)", failures.first())
        }
    }


    //--------------------
    // Implementation
    //---------------------
    private trackChange(String msg, Object data) {
        trackService.track(
            msg: msg,
            category: 'Views',
            data: data instanceof JsonBlob ?
                [name: data.name, token: data.token, isGlobal: data.owner == null] :
                data
        )
    }

    private Map getStateFromBlob(JsonBlob blob, String viewInstance) {
        def rawState = parseObject(blob?.value),
            ret = [
                userPinned: rawState?.userPinned ?: [:],
                autoSave  : rawState?.autoSave ?: false
            ]
        Map currentView = rawState?.currentView as Map
        if (currentView?.containsKey(viewInstance)) {
            ret.currentView = currentView[viewInstance]
        }
        return ret
    }


}
