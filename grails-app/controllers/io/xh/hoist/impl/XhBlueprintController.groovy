/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.impl

import io.xh.hoist.BaseController
import io.xh.hoist.blueprint.Blueprint
import io.xh.hoist.blueprint.BlueprintPersonalization
import io.xh.hoist.blueprint.BlueprintPublish
import io.xh.hoist.blueprint.BlueprintService
import io.xh.hoist.blueprint.BlueprintStaleBaseException
import io.xh.hoist.security.AccessAll

import static io.xh.hoist.json.JSONParser.parseArray
import static io.xh.hoist.json.JSONParser.parseObject

/**
 * Endpoints for a client `BlueprintRepository`, one per repository method, at `xhBlueprint/*`.
 * JSON matches the library's types, with a blueprint's token as its `id`. Access is checked by
 * {@link BlueprintService} for every call.
 */
@AccessAll
class XhBlueprintController extends BaseController {

    BlueprintService blueprintService

    def list() {
        renderJSON(blueprintService.list().collect { summaryJSON(it) })
    }

    def canCreate() {
        renderJSON(canCreate: blueprintService.canCreate())
    }

    /** Params: `id`, `as` ('draft' or 'publish', the default) and an optional publish `version`. */
    def open(String id) {
        if (params.as == 'draft') {
            renderJSON(draftRecord(blueprintService.getDraft(id)))
        } else {
            def publish = blueprintService.getPublish(id, params.version as Integer)
            renderJSON(publish ? publishRecord(publish) : null)
        }
    }

    def create() {
        Map body = parseRequestJSON()
        renderJSON(draftRecord(blueprintService.create(body.name as String, body.description as String, body.blueprint)))
    }

    def saveDraft(String id) {
        Map body = parseRequestJSON()
        try {
            long version = blueprintService.saveDraft(id, body.blueprint, body.base as long, body.renames as List)
            renderJSON(version: version, issues: [])
        } catch (BlueprintStaleBaseException e) {
            renderJSON(issues: [staleBaseIssue(e)])
        }
    }

    def publish(String id) {
        Map body = parseRequestJSON()
        try {
            BlueprintPublish publish = blueprintService.publish(id, body.base as long, body.note as String)
            renderJSON(publish: publishJSON(publish), draftVersion: publish.blueprint.draftVersion, issues: [])
        } catch (BlueprintStaleBaseException e) {
            renderJSON(issues: [staleBaseIssue(e)])
        }
    }

    def listPublishes(String id) {
        renderJSON(blueprintService.listPublishes(id).collect {
            compact(version: it.number, published: (it.dateCreated as Date).time, publishedBy: it.publishedBy, note: it.note, renames: it.renames)
        })
    }

    def copy(String id) {
        Map body = parseRequestJSON(),
            from = (body.from ?: [:]) as Map
        Blueprint copy = blueprintService.copy(id, body.name as String, (from.as ?: 'publish') as String, from.version as Integer)
        renderJSON(copy ? draftRecord(copy) : null)
    }

    def delete(String id) {
        blueprintService.delete(id)
        renderSuccess()
    }

    def personalizations(String blueprintId) {
        renderJSON(blueprintService.listPersonalizations(blueprintId).collect { personalizationJSON(it, blueprintId) })
    }

    def savePersonalization() {
        Map body = parseRequestJSON()
        String blueprintId = body.blueprintId
        def saved = blueprintService.savePersonalization(blueprintId, body.preset as String, body.ops as List, body.publishVersion as int)
        renderJSON(personalizationJSON(saved, blueprintId))
    }

    def updateAccess(String id) {
        Map body = parseRequestJSON()
        blueprintService.updateAccess(id, body.writers as List, body.readers as List)
        renderSuccess()
    }

    //------------------
    // Implementation
    //------------------
    private Map summaryJSON(Map s) {
        compact(
            id: s.token,
            name: s.name,
            description: s.description,
            draftVersion: s.draftVersion,
            publishedVersion: s.publishedVersion,
            access: s.access,
            canPublish: s.canPublish,
            canShare: s.canManage,
            updated: (s.lastUpdated as Date)?.time,
            updatedBy: s.lastUpdatedBy,
            copiedFrom: s.copiedFromToken ? [id: s.copiedFromToken, version: s.copiedFromVersion, as: s.copiedFromKind] : null,
            sharing: s.canManage ? [writers: s.writers, readers: s.readers] : null
        ) + [publishedVersion: s.publishedVersion]
    }

    private Map draftRecord(Blueprint blueprint) {
        summaryJSON(blueprintService.summaryOf(blueprint)) + [
            blueprint: parseObject(blueprint.draft),
            kind: 'draft',
            version: blueprint.draftVersion,
            pendingRenames: parseArray(blueprint.pendingRenames)
        ]
    }

    private Map publishRecord(BlueprintPublish publish) {
        summaryJSON(blueprintService.summaryOf(publish.blueprint)) + [
            blueprint: parseObject(publish.content),
            kind: 'publish',
            version: publish.number,
            pendingRenames: []
        ]
    }

    private Map publishJSON(BlueprintPublish publish) {
        compact(
            version: publish.number,
            published: publish.dateCreated.time,
            publishedBy: publish.publishedBy,
            note: publish.note,
            renames: parseArray(publish.renames)
        )
    }

    private Map personalizationJSON(BlueprintPersonalization p, String blueprintId) {
        [
            blueprintId: blueprintId,
            preset: p.preset == BlueprintPersonalization.NO_PRESET ? null : p.preset,
            ops: parseArray(p.ops),
            publishVersion: p.publishVersion
        ]
    }

    private Map staleBaseIssue(BlueprintStaleBaseException e) {
        [code: 'edit.staleBase', severity: 'error', path: '', message: e.message]
    }

    // Leave out unset optional fields, as the library's types mark them optional, not nullable.
    private Map compact(Map m) {
        m.findAll { it.value != null }
    }
}
