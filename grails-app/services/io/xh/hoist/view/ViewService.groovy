/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.view

import groovy.transform.CompileStatic
import io.xh.hoist.BaseService
import io.xh.hoist.jsonblob.JsonBlob
import io.xh.hoist.jsonblob.JsonBlobService
import io.xh.hoist.track.TrackService

import static io.xh.hoist.json.JSONParser.parseObject

/**
 * Manage all View state for Hoist's built-in client-side views
 */
@CompileStatic
class ViewService extends BaseService {

    JsonBlobService jsonBlobService
    TrackService trackService

    static final String STATE_BLOB_NAME = 'xhUserState';

    //----------------------------
    // ViewManager state + support
    //-----------------------------
    /**
     * Get all the views, and the current view state for a user
     */
    Map getAllData(String type, String viewInstance, String username = username) {
        def blobs = jsonBlobService.list(type, username).split { it.name == STATE_BLOB_NAME }
        def (rawState, views) = [blobs[0], blobs[1]]

        return [
            state: getStateFromBlob(rawState ? rawState.first() : null, viewInstance),
            views: views*.formatForClient(false)
        ]
    }

    /** Update state for this user */
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

        def blob = jsonBlobService.createOrUpdate(type, STATE_BLOB_NAME, [value: newValue], username)
        return getStateFromBlob(blob, viewInstance)
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
        def ret = jsonBlobService.create([
            type       : data.type,
            name       : data.name,
            description: data.description,
            acl        : data.isShared ? '*' : null,
            meta       : [group: data.group, isShared: data.isShared],
            value      : data.value
        ], username)

        if (data.isPinned) {
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

    /** Update a views metadata */
    Map updateInfo(String token, Map data, String username = username) {
        def existing = jsonBlobService.get(token, username),
            existingMeta = parseObject(existing.meta),
            isGlobal = !existing.owner,
            isShared = data.containsKey('isShared') ? data.isShared : existingMeta.isShared;

        def ret = jsonBlobService.update(
            token,
            [
                *   : data,
                acl : isGlobal || isShared ? '*' : null,
                meta: isGlobal ?
                    [group: data.group, isDefaultPinned: !!data.isDefaultPinned] :
                    [group: data.group, isShared: !!data.isShared],
            ],
            username
        )
        trackChange('Updated View Info', ret)
        ret.formatForClient(true)
    }

    /** Update a views value */
    Map updateValue(String token, Map value, String username = username) {
        def ret = jsonBlobService.update(token, [value: value], username);
        if (ret.owner == null) {
            trackChange('Updated Global View definition', ret);
        }
        ret.formatForClient(true)
    }

    /** Make a view global */
    Map makeGlobal(String token, String username = username) {
        def existing = jsonBlobService.get(token, username),
            meta = parseObject(existing.meta)?.findAll { it.key != 'isShared' },
            ret = jsonBlobService.update(token, [owner: null, acl: '*', meta: meta], username)

        this.trackChange('Made View Global', ret)
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
