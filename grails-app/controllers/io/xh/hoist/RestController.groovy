/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2018 Extremely Heavy Industries Inc.
 */

package io.xh.hoist

import grails.validation.ValidationException
import groovy.util.logging.Slf4j
import io.xh.hoist.json.JSON
import org.grails.web.json.JSONObject

@Slf4j
abstract class RestController extends BaseController {

    def trackService,
        messageSource

    static trackChanges = false
    static restTarget = null // Implementations set to value of GORM domain class they are editing.

    def create() {
        def data = request.JSON.data
        preprocessSubmit(data as JSONObject)

        def obj = restTargetVal.newInstance(data)
        try {
            doCreate(obj, data)
            noteChange(obj, 'CREATE')
            renderJSON(success:true, data:obj)
        } catch (ValidationException ignored) {
            throw new RuntimeException(errorsString(obj))
        }
    }

    def read() {
        def query = params.query ? JSON.parse(params.query) as Map : [:],
            ret = params.id ? [restTargetVal.get(params.id)] : doList(query)
        renderJSON(success:true, data:ret)
    }

    def update() {
        def data = params.data ? new JSONObject(params.data) : new JSONObject(request.JSON.data)
        preprocessSubmit(data)

        def obj = restTargetVal.get(data.id)
        try {
            doUpdate(obj, data)
            noteChange(obj, 'UPDATE')
            renderJSON(success:true, data:obj)
        } catch (ValidationException ignored) {
            obj.discard()
            throw new RuntimeException(errorsString(obj))
        }
    }

    def bulkUpdate() {
        def ids = params.list('ids'),
            newParams = JSON.parse(params.newParams),
            successCount = 0,
            failCount = 0,
            target = restTargetVal,
            obj = null

        ids.each { id ->
            try {
                obj = target.get(id)
                doUpdate(obj, newParams)
                noteChange(obj, 'UPDATE')
                successCount++
            } catch (Exception e) {
                failCount++
                if (e instanceof ValidationException) {
                    log.error(errorsString(obj))
                } else {
                    log.error("Unexpected exception updating ${obj}", e)
                }
            }
        }

        renderJSON(success:successCount, fail:failCount)
    }

    def delete() {
        def obj = restTargetVal.get(params.id)
        doDelete(obj)
        noteChange(obj, 'DELETE')
        renderJSON(success:true)
    }

    def bulkDelete() {
        def ids = params.list('ids'),
            successCount = 0,
            failCount = 0,
            target = restTargetVal

        ids.each {id ->
            try {
                target.withTransaction{ status ->
                    def obj = target.get(id)
                    doDelete(obj)
                    noteChange(obj, 'DELETE')
                    successCount++
                }
            } catch (Exception ignored) {
                failCount++
            }
        }

        renderJSON(success:successCount, fail:failCount)
    }

    
    //--------------------------------
    // Template Methods for Override
    //--------------------------------
    protected void doCreate(Object obj, Object data) {
        obj.save(flush:true)
    }

    protected List doList(Map query) {
        if (query) throw new RuntimeException('You must implement an override of doList() to use query fields.')
        return restTargetVal.list()
    }

    protected void doUpdate(Object obj, Object data) {
        bindData(obj, data)
        obj.save(flush:true)
    }

    protected void doDelete(Object obj) {
        obj.delete(flush:true)
    }

    protected void preprocessSubmit(JSONObject submit) {

    }

    protected void noteChange(Object obj, String changeType) {
        if (trackChangesVal) trackChange(obj, getChangeVerb(changeType))
    }

    protected void trackChange(Object obj, String changeType) {
        def className = obj.class.simpleName
        trackService.track(
                msg: "$changeType $className",
                category: 'Audit',
                data: [ id: obj.id ]
        )
    }

    protected String errorsString(gormObject) {
        return 'Could not save: ' + allErrors(gormObject).join(', ')
    }

    protected List allErrors(gormObject) {
        return gormObject.errors.allErrors.collect{error ->
            messageSource.getMessage(error, Locale.US)
        }
    }

    private static String getChangeVerb(String changeType) {
        switch(changeType) {
            case 'CREATE':  return 'Created'
            case 'UPDATE':  return 'Updated'
            case 'DELETE':  return 'Deleted'
            default:        return 'Changed'
        }
    }

    protected Class getRestTargetVal() {
        return this.class.restTarget
    }

    protected boolean getTrackChangesVal() {
        return this.class.trackChanges
    }
    
}
