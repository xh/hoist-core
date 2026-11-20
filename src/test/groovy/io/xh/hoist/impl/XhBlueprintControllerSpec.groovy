/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.impl

import grails.testing.gorm.DataTest
import grails.testing.web.controllers.ControllerUnitTest
import io.xh.hoist.blueprint.Blueprint
import io.xh.hoist.blueprint.BlueprintConfig
import io.xh.hoist.blueprint.BlueprintPersonalization
import io.xh.hoist.blueprint.BlueprintPublish
import io.xh.hoist.blueprint.BlueprintService
import io.xh.hoist.test.HoistJson
import org.grails.plugins.testing.GrailsMockHttpServletRequest
import org.grails.plugins.testing.GrailsMockHttpServletResponse
import org.grails.web.servlet.mvc.GrailsWebRequest
import org.springframework.web.context.request.RequestContextHolder
import io.xh.hoist.test.HoistUnitTest
import io.xh.hoist.test.fakes.TestUser
import spock.lang.Specification

/**
 * Tests the JSON {@link XhBlueprintController} sends and accepts, which mirrors the library's
 * `BlueprintRepository` types. The rules behind it are covered by `BlueprintServiceSpec`.
 */
class XhBlueprintControllerSpec extends Specification implements ControllerUnitTest<XhBlueprintController>, DataTest, HoistUnitTest {

    static final Map BP = [schema: [blueprint: 1]]

    TestUser author = new TestUser('owner', ['DESIGNER']),
             reader = new TestUser('reader')

    Class[] getDomainClassesToMock() { [Blueprint, BlueprintPublish, BlueprintPersonalization] }

    def setup() {
        controller.blueprintService = defineService(BlueprintService)
        testConfigService.registerTypedConfig('xhBlueprintConfig', BlueprintConfig)
        testConfigService.set('xhBlueprintConfig', [enabled: true, createRoles: ['DESIGNER']])
        loginAs(author)
    }

    def 'create returns the draft record, with sharing for its owner'() {
        when:
        def record = call('create', [name: 'Risk', blueprint: BP])

        then:
        record.id ==~ /[0-9a-f-]{8}/
        record.name == 'Risk'
        !record.containsKey('description')
        record.draftVersion == 1
        record.publishedVersion == null
        record.containsKey('publishedVersion')
        record.access == 'write'
        record.canPublish == true
        record.canShare == true
        record.updated instanceof Long
        record.updatedBy == 'owner'
        record.sharing == [writers: [], readers: []]
        record.blueprint == BP
        record.kind == 'draft'
        record.version == 1
        record.pendingRenames == []
    }

    def 'a draft save returns its new version, or a staleBase issue'() {
        given:
        String id = call('create', [name: 'Risk', blueprint: BP]).id
        def rename = [kind: 'element', from: 'a', to: 'b']

        expect:
        call('saveDraft', [blueprint: BP, base: 1, renames: [rename]], [id: id]) == [version: 2, issues: []]

        when:
        def stale = call('saveDraft', [blueprint: BP, base: 1, renames: []], [id: id])

        then:
        response.status == 200
        stale.keySet() == ['issues'] as Set
        stale.issues[0].subMap('code', 'severity', 'path') == [code: 'edit.staleBase', severity: 'error', path: '']
        stale.issues[0].message.contains('version 2')
    }

    def 'publish returns the publish and the draft\'s new version'() {
        given:
        String id = call('create', [name: 'Risk', blueprint: BP]).id
        def rename = [kind: 'variable', from: 'x', to: 'y']
        call('saveDraft', [blueprint: BP, base: 1, renames: [rename]], [id: id])

        when:
        def result = call('publish', [base: 2, note: 'First'], [id: id])

        then:
        result.draftVersion == 3
        result.issues == []
        result.publish.version == 1
        result.publish.published instanceof Long
        result.publish.publishedBy == 'owner'
        result.publish.note == 'First'
        result.publish.renames == [rename]

        when:
        def publishes = call('listPublishes', null, [id: id])

        then:
        publishes.size() == 1
        publishes[0].subMap('version', 'publishedBy', 'note', 'renames') == [version: 1, publishedBy: 'owner', note: 'First', renames: [rename]]

        expect:
        call('publish', [base: 2], [id: id]).issues[0].code == 'edit.staleBase'
    }

    def 'open gives a publish by default, a draft on request, and null with no publish'() {
        given:
        String id = call('create', [name: 'Risk', blueprint: BP]).id

        expect:
        call('open', null, [id: id]) == null
        call('open', null, [id: id, as: 'draft']).kind == 'draft'

        when:
        call('publish', [base: 1], [id: id])
        def record = call('open', null, [id: id, as: 'publish', version: '1'])

        then:
        record.kind == 'publish'
        record.version == 1
        record.blueprint == BP
        record.pendingRenames == []
        record.publishedVersion == 1
        record.draftVersion == 2
    }

    def 'readers get summaries without sharing, and may personalize'() {
        given:
        String id = call('create', [name: 'Risk', blueprint: BP]).id
        call('updateAccess', [writers: [], readers: ['reader']], [id: id])
        call('publish', [base: 1], [id: id])

        when:
        loginAs(reader)
        def list = call('list')

        then:
        list.size() == 1
        list[0].id == id
        list[0].access == 'read'
        list[0].canPublish == false
        list[0].canShare == false
        !list[0].containsKey('sharing')
        call('canCreate') == [canCreate: false]

        when:
        def ops = [[op: 'set', path: '/variables/theme/default', value: 'dark']]
        def saved = call('savePersonalization', [blueprintId: id, preset: null, ops: ops, publishVersion: 1])

        then:
        saved == [blueprintId: id, preset: null, ops: ops, publishVersion: 1]
        call('personalizations', null, [blueprintId: id]) == [saved]
    }

    def 'a copy records its source'() {
        given:
        String id = call('create', [name: 'Risk', description: 'Desk', blueprint: BP]).id
        call('publish', [base: 1], [id: id])

        when:
        def copy = call('copy', [name: 'Mine'], [id: id])

        then:
        copy.name == 'Mine'
        copy.description == 'Desk'
        copy.kind == 'draft'
        copy.copiedFrom == [id: id, version: 1, as: 'publish']
    }

    //------------------
    // Implementation
    //------------------
    // Run an action on a fresh request, with a JSON body and params, and return its parsed JSON.
    private Object call(String action, Map body = null, Map params = [:]) {
        def req = new GrailsMockHttpServletRequest()
        req.method = body != null ? 'POST' : 'GET'
        if (body != null) req.content = HoistJson.serialize(body).bytes
        params.each { k, v -> if (v != null) req.addParameter(k as String, v as String) }
        webRequest = new GrailsWebRequest(req, new GrailsMockHttpServletResponse(), servletContext, applicationContext)
        RequestContextHolder.requestAttributes = webRequest

        def arg = params.id ?: params.blueprintId
        arg != null ? controller."$action"(arg) : controller."$action"()
        return HoistJson.parse(response.text)
    }
}
