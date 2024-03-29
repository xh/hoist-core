/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */

package io.xh.hoist

import grails.gorm.transactions.Transactional
import grails.validation.ValidationException
import io.xh.hoist.json.JSONParser

@Transactional
abstract class RestController extends BaseController {

    def trackService

    static trackChanges = false
    static restTarget = null // Implementations set to value of GORM domain class they are editing.

    def create() {
        def data = parseRequestJSON().data
        preprocessSubmit(data)

        def obj = restTargetVal.newInstance(data)
        doCreate(obj, data)
        noteChange(obj, 'CREATE')
        renderJSON(success:true, data:obj)
    }

    def read() {
        def query = params.query ? JSONParser.parseObject(params.query) : [:],
            ret = params.id ? [restTargetVal.get(params.id)] : doList(query)
        renderJSON(success:true, data:ret)
    }

    def update() {
        def data = parseRequestJSON().data
        preprocessSubmit(data)

        def obj = restTargetVal.get(data.id)
        try {
            doUpdate(obj, data)
            noteChange(obj, 'UPDATE')
            renderJSON(success:true, data:obj)
        } catch (ValidationException ex) {
            obj.discard()
            throw ex
        }
    }

    def bulkUpdate() {
        def body = parseRequestJSON(),
            ids = body.ids,
            newParams = body.newParams,
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
                    e = new io.xh.hoist.exception.ValidationException(e)
                    logDebug("Validation exception updating ${obj}", e)
                } else {
                    logError("Unexpected exception updating ${obj}", e)
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

    protected void preprocessSubmit(Map submit) {

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
