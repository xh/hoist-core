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
import io.xh.hoist.exception.RoutineRuntimeException
import io.xh.hoist.security.AccessAll

import static io.xh.hoist.json.JSONParser.parseArray
import static io.xh.hoist.json.JSONParser.parseObject

/**
 * Endpoints for a client `BlueprintRepository`, one per repository method, at `xhBlueprint/*`.
 * JSON matches the library's types, with a blueprint's token as its `id` - no database id is sent
 * or read. Every action needs a `type` param, in the query string for POSTs too, which scopes it
 * as described on {@link BlueprintService}. Access is checked by that service for every call.
 */
@AccessAll
class XhBlueprintController extends BaseController {

    BlueprintService blueprintService

    def list() {
        renderJSON(blueprintService.list(blueprintType).collect { summaryJSON(it) })
    }

    def canCreate() {
        renderJSON(canCreate: blueprintService.canCreate())
    }

    /**
     * Params: `id`, `as` ('draft' or 'publish', the default) and an optional publish `version`.
     * Null if the blueprint is missing or the user may not see that version, which look the same.
     */
    def open(String id) {
        if (params.as == 'draft') {
            Blueprint draft = blueprintService.getDraft(blueprintType, id)
            renderJSON(draft ? draftRecord(draft) : null)
        } else {
            BlueprintPublish publish = blueprintService.getPublish(blueprintType, id, optionalInt(params.version, 'version'))
            renderJSON(publish ? publishRecord(publish) : null)
        }
    }

    def create() {
        Map body = parseRequestJSON()
        renderJSON(draftRecord(blueprintService.create(
            blueprintType,
            requiredString(body.name, 'name'),
            optionalString(body.description, 'description'),
            body.blueprint,
            [],
            [],
            body.copiedFrom
        )))
    }

    def saveDraft(String id) {
        Map body = parseRequestJSON()
        try {
            long version = blueprintService.saveDraft(blueprintType, id, body.blueprint, requiredLong(body.base, 'base'), body.renames)
            renderJSON(version: version, issues: [])
        } catch (BlueprintStaleBaseException e) {
            renderJSON(issues: [staleBaseIssue(e)])
        }
    }

    def publish(String id) {
        Map body = parseRequestJSON()
        try {
            BlueprintPublish publish = blueprintService.publish(blueprintType, id, requiredLong(body.base, 'base'), optionalString(body.note, 'note'))
            renderJSON(publish: publishJSON(publish), draftVersion: publish.blueprint.draftVersion, issues: [])
        } catch (BlueprintStaleBaseException e) {
            renderJSON(issues: [staleBaseIssue(e)])
        }
    }

    def listPublishes(String id) {
        renderJSON(blueprintService.listPublishes(blueprintType, id).collect {
            compact(version: it.number, published: (it.dateCreated as Date).time, publishedBy: it.publishedBy, note: it.note, label: it.label, pinned: it.pinned, renames: it.renames)
        })
    }

    def updatePublish(String id) {
        Map body = parseRequestJSON()
        int version = requiredLong(body.version, 'version') as int
        renderJSON(publishJSON(blueprintService.updatePublish(blueprintType, id, version, body.findAll { it.key != 'version' })))
    }

    def copy(String id) {
        Map body = parseRequestJSON()
        if (body.from != null && !(body.from instanceof Map)) throw new RoutineRuntimeException("'from' must be {as, version}")
        Map from = (body.from ?: [:]) as Map
        Blueprint copy = blueprintService.copy(
            blueprintType, id, requiredString(body.name, 'name'), (optionalString(from.as, 'from.as') ?: 'publish'), optionalInt(from.version, 'from.version')
        )
        renderJSON(copy ? draftRecord(copy) : null)
    }

    def delete(String id) {
        blueprintService.delete(blueprintType, id)
        renderSuccess()
    }

    def personalizations(String blueprintId) {
        renderJSON(blueprintService.listPersonalizations(blueprintType, blueprintId).collect { personalizationJSON(it, blueprintId) })
    }

    def savePersonalization() {
        Map body = parseRequestJSON()
        String blueprintId = requiredString(body.blueprintId, 'blueprintId')
        def saved = blueprintService.savePersonalization(blueprintType, blueprintId, body.preset, body.ops, requiredLong(body.publishVersion, 'publishVersion') as int)
        renderJSON(personalizationJSON(saved, blueprintId))
    }

    /** Body: `{name, description?}`. A description key set to null clears it. Returns the summary. */
    def rename(String id) {
        Map body = parseRequestJSON()
        renderJSON(summaryJSON(blueprintService.summaryOf(blueprintService.rename(blueprintType, id, body))))
    }

    def updateAccess(String id) {
        Map body = parseRequestJSON()
        blueprintService.updateAccess(blueprintType, id, body.writers, body.readers)
        renderSuccess()
    }

    //------------------
    // Implementation
    //------------------
    private String getBlueprintType() {
        requiredString(params.type, 'type')
    }

    private Map summaryJSON(Map s) {
        compact(
            id: s.token,
            type: s.type,
            name: s.name,
            description: s.description,
            owner: s.owner,
            draftVersion: s.draftVersion,
            publishedVersion: s.publishedVersion,
            access: s.access,
            canPublish: s.canPublish,
            canShare: s.canManage,
            canShareGlobally: s.canShareGlobally,
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
            label: publish.label,
            pinned: publish.pinned,
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

    private static long requiredLong(Object value, String name) {
        if (!(value instanceof Number)) throw new RoutineRuntimeException("'$name' must be a number, got '$value'")
        return (value as Number).longValue()
    }

    private static Integer optionalInt(Object value, String name) {
        if (value == null || value == '') return null
        if (value instanceof Number) return (value as Number).intValue()
        if (value instanceof String && value.isInteger()) return value as Integer
        throw new RoutineRuntimeException("'$name' must be a whole number, got '$value'")
    }

    private static String requiredString(Object value, String name) {
        if (!(value instanceof String) || !value) throw new RoutineRuntimeException("'$name' must be a non-empty string")
        return value
    }

    private static String optionalString(Object value, String name) {
        if (value != null && !(value instanceof String)) throw new RoutineRuntimeException("'$name' must be a string")
        return value as String
    }

    private Map staleBaseIssue(BlueprintStaleBaseException e) {
        [code: 'edit.staleBase', severity: 'error', path: '', message: e.message]
    }

    // Leave out unset optional fields, as the library's types mark them optional, not nullable.
    private Map compact(Map m) {
        m.findAll { it.value != null }
    }
}
