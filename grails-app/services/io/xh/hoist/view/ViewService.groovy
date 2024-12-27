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
    Map getAllData(String type, String viewInstance) {
        def blobs = jsonBlobService.list(type).split { it.name == STATE_BLOB_NAME }
        def (rawState, views) = [blobs[0], blobs[1]]

        // Transform state
        rawState = rawState ? parseObject(rawState[0].value) : null
        def state = [
            userPinned: rawState?.userPinned ?: [:],
            autoSave: rawState?.autoSave ?: false
        ]
        Map currentView = rawState?.currentView as Map
        if (currentView?.containsKey(viewInstance)) {
            state.currentView = currentView[viewInstance]
        }

        // Transform views
        views = views*.formatForClient(false)

        return [state: state, views: views]
    }

    /** Update state for this user */
    void updateState(String type, String viewInstance, Map update) {
        def currBlob = jsonBlobService.find(type, STATE_BLOB_NAME, username),
            currValue = parseObject(currBlob.value),
            newValue = [
                currentView: currValue?.currentView ?: [:],
                userPinned : currValue?.userPinned ?: [:],
                autoSave   : currValue?.autoSave ?: false
            ]

        if (update.containsKey('currentView')) newValue.currentView[viewInstance] = update.currentView
        if (update.containsKey('userPinned')) newValue.userPinned = update.userPinned
        if (update.containsKey('autoSave')) newValue.autoSave = update.autoSave

        jsonBlobService.createOrUpdate(type, STATE_BLOB_NAME, [value: newValue])
    }

    //---------------------------
    // Individual View management
    //----------------------------
    /** Fetch the latest version of a view. */
    Map get(String token) {
        jsonBlobService.get(token).formatForClient(true)
    }

    /** Fetch the latest version of a view. */
    Map create(Map data) {
        def ret = jsonBlobService.create(
            type: data.type,
            name: data.name,
            description: data.description,
            acl: data.isShared ? '*' : null,
            meta: [group: data.group, isShared: data.isShared],
            value: data.value
        )
        trackChange('Created View', ret)
        ret.formatForClient(true)
    }

    /** Update a views metadata */
    Map updateInfo(String token, Map data) {
        def existing = jsonBlobService.get(token),
            existingMeta = parseObject(existing.meta),
            isGlobal = existingMeta.isGlobal,
            isShared = data.containsKey('isShared') ? data.isShared : existingMeta.isShared;

        def ret = jsonBlobService.update(
            token,
            [
                *: data,
                acl: isGlobal || isShared ? '*' : null,
                meta: isGlobal ?
                    [group: data.group, isDefaultPinned: !!data.isDefaultPinned]:
                    [group: data.group, isShared: !!data.isShared],
            ]
        )
        trackChange('Updated View Info', ret)
        ret.formatForClient(true)
    }

    /** Update a views value */
    Map updateValue(String token, Map value) {
        def ret = jsonBlobService.update(token, [value: value]);
        if (ret.owner == null) {
            trackChange('Updated Global View definition', ret);
        }
        ret.formatForClient(true)
    }

    /** Make a view global */
    Map makeGlobal(String token) {
        def existing = jsonBlobService.get(token),
            meta = parseObject(existing.meta)?.findAll { it.key != 'isShared' },
            ret = jsonBlobService.update(token, [owner: null, acl: '*', meta: meta])

        this.trackChange('Made View Global', ret)
        ret.formatForClient(true)
    }


    /** Bulk Delete views */
    void delete(List<String> tokens) {
        List<Exception> failures = []
        tokens.each {
            try {
                jsonBlobService.archive(it)
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
}
