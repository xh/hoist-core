/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.blueprint

import grails.testing.gorm.DataTest
import grails.testing.services.ServiceUnitTest
import io.xh.hoist.exception.NotAuthorizedException
import io.xh.hoist.exception.NotFoundException
import io.xh.hoist.exception.RoutineRuntimeException
import io.xh.hoist.test.HoistUnitTest
import io.xh.hoist.test.fakes.TestUser
import spock.lang.Specification

import static io.xh.hoist.json.JSONParser.parseArray
import static io.xh.hoist.json.JSONParser.parseObject

/**
 * Tests the rules of {@link BlueprintService} - access, versions, publishes, copies, cascades and
 * structural checks - on the in-memory datastore. Row locks and unique indexes need a real
 * database, and are covered by {@link BlueprintServiceHibernateSpec}.
 */
class BlueprintServiceSpec extends Specification implements ServiceUnitTest<BlueprintService>, DataTest, HoistUnitTest {

    static final Map BP = [schema: [blueprint: 1], elements: [root: [component: 'dashCanvas']]]
    static final Map RENAME = [kind: 'element', from: 'grid', to: 'posGrid']

    TestUser owner = new TestUser('owner', ['DESIGNER']),
             reader = new TestUser('reader'),
             designer = new TestUser('designer', ['DESIGNER']),
             editor = new TestUser('editor', ['EDITOR']),
             admin = new TestUser('admin', ['HOIST_ADMIN']),
             stranger = new TestUser('stranger')

    Class[] getDomainClassesToMock() { [Blueprint, BlueprintPublish, BlueprintPersonalization] }

    def setup() {
        testConfigService.registerTypedConfig('xhBlueprintConfig', BlueprintConfig)
        setConfig()
        loginAs(owner)
    }

    def 'every call is refused until storage is enabled'() {
        given:
        setConfig(enabled: false)

        when:
        service.list()

        then:
        def e = thrown(RoutineRuntimeException)
        e.message.contains('not enabled')
    }

    def 'creating needs a create role, and the creator owns the blueprint'() {
        when:
        def bp = service.create('Risk', 'Desk risk', BP)

        then:
        bp.owner == 'owner'
        bp.draftVersion == 1
        bp.publishedVersion == null
        parseObject(bp.draft) == BP
        info(bp.token).subMap('access', 'canPublish', 'canManage') == [access: 'write', canPublish: true, canManage: true]
        service.canCreate()

        when:
        loginAs(stranger)

        then:
        !service.canCreate()

        when:
        service.create('Mine', null, BP)

        then:
        thrown(NotAuthorizedException)
    }

    def 'a draft save needs the current base, and a stale one changes nothing'() {
        given:
        def token = service.create('Risk', null, BP).token

        expect:
        service.saveDraft(token, [*: BP, title: 'v2'], 1, [RENAME]) == 2

        when:
        service.saveDraft(token, [*: BP, title: 'stale'], 1, [])

        then:
        def e = thrown(BlueprintStaleBaseException)
        e.base == 1
        e.current == 2

        and:
        def draft = service.getDraft(token)
        draft.draftVersion == 2
        parseObject(draft.draft).title == 'v2'
        parseArray(draft.pendingRenames) == [RENAME]
    }

    def 'each publish takes the next number with the pending renames, and bumps the draft version'() {
        given:
        def token = service.create('Risk', null, BP).token
        service.saveDraft(token, BP, 1, [RENAME])

        when:
        def first = service.publish(token, 2, 'First')

        then:
        first.number == 1
        first.note == 'First'
        parseArray(first.renames) == [RENAME]
        first.blueprint.draftVersion == 3

        when: 'a save based on the draft from before the publish'
        service.saveDraft(token, BP, 2, [RENAME])

        then: 'is refused, rather than re-adding the published rename'
        thrown(BlueprintStaleBaseException)

        when:
        def second = service.publish(token, 3)

        then:
        second.number == 2
        parseArray(second.renames) == []

        and:
        def draft = service.getDraft(token)
        draft.draftVersion == 4
        draft.publishedVersion == 2
        draft.pendingRenames == '[]'
        parseObject(draft.draft) == BP
        service.listPublishes(token)*.number == [1, 2]
        service.listPublishes(token)[0].renames == [RENAME]

        when:
        service.publish(token, 3)

        then:
        thrown(BlueprintStaleBaseException)
    }

    def 'readers see only the latest publish, and only once there is one'() {
        given:
        def token = service.create('Risk', null, BP, [], ['reader']).token

        when:
        loginAs(reader)

        then:
        service.list() == []
        info(token).subMap('access', 'canPublish', 'canManage') == [access: 'read', canPublish: false, canManage: false]
        !info(token).containsKey('writers') && !info(token).containsKey('readers')
        service.getPublish(token) == null

        when:
        loginAs(owner)
        service.publish(token, 1)
        service.saveDraft(token, [*: BP, title: 'later'], 2, [])
        service.publish(token, 3)
        loginAs(reader)

        then:
        service.list()*.token == [token]
        service.list()[0].access == 'read'
        service.getPublish(token).number == 2
        service.getDraft(token) == null
        service.getPublish(token, 1) == null

        when:
        call(service)

        then:
        thrown(NotAuthorizedException)

        where:
        call << [
            { BlueprintService s -> s.listPublishes(s.list()[0].token as String) },
            { BlueprintService s -> s.publish(s.list()[0].token as String, 2) },
            { BlueprintService s -> s.saveDraft(s.list()[0].token as String, BP, 2, []) }
        ]
    }

    def 'grantees can be usernames, roles or everyone, and admins have write everywhere'() {
        given:
        def token = service.create('Risk', null, BP, ['role:EDITOR'], ['*']).token

        expect:
        accessAs(editor, token) == 'write'
        accessAs(stranger, token) == 'read'
        accessAs(admin, token) == 'write'
        accessAs(owner, token) == 'write'
    }

    def 'only the owner or an admin may manage access or delete'() {
        given:
        def token = service.create('Risk', null, BP, ['editor'], []).token

        when:
        loginAs(editor)
        service.updateAccess(token, [], [])

        then:
        thrown(NotAuthorizedException)

        when:
        service.delete(token)

        then:
        thrown(NotAuthorizedException)

        when:
        loginAs(admin)
        def bp = service.updateAccess(token, ['designer'], ['role:EDITOR'])

        then:
        parseArray(bp.writers) == ['designer']
        accessAs(editor, token) == 'read'
    }

    def 'publishRoles narrow publishing for writers, but not for admins'() {
        given:
        setConfig(publishRoles: ['PUBLISHER'])
        def token = service.create('Risk', null, BP).token

        when:
        service.publish(token, 1)

        then:
        thrown(NotAuthorizedException)
        !info(token).canPublish

        when:
        loginAs(admin)

        then:
        service.publish(token, 1).number == 1
    }

    def 'a copy takes the latest publish by default, records its source, and needs a create role'() {
        given:
        def token = service.create('Risk', 'Desk risk', BP, [], ['designer']).token

        expect: 'nothing to copy before a publish'
        service.copy(token, 'Copy') == null

        when:
        service.saveDraft(token, [*: BP, title: 'published'], 1, [])
        service.publish(token, 2)
        loginAs(designer)
        def copy = service.copy(token, 'My copy')

        then:
        copy.owner == 'designer'
        copy.name == 'My copy'
        copy.description == 'Desk risk'
        copy.draftVersion == 1
        copy.publishedVersion == null
        parseObject(copy.draft).title == 'published'
        [copy.copiedFromToken, copy.copiedFromVersion, copy.copiedFromKind] == [token, 1L, 'publish']

        when: 'a reader copying the draft'
        service.copy(token, 'Draft copy', 'draft')

        then:
        thrown(NotAuthorizedException)

        when: 'a reader without a create role'
        loginAs(reader)
        service.copy(token, 'No')

        then:
        thrown(NotAuthorizedException)
    }

    def 'deleting a blueprint deletes its publishes and every user\'s personalizations'() {
        given:
        def token = service.create('Risk', null, BP, [], ['*']).token
        def other = service.create('Other', null, BP, [], ['*']).token
        [token, other].each { service.publish(it, 1) }
        [owner, reader].each { user ->
            loginAs(user)
            [token, other].each { service.savePersonalization(it, null, [], 1) }
        }
        loginAs(owner)

        when:
        service.delete(token)

        then:
        !Blueprint.findByToken(token)
        BlueprintPublish.count() == 1
        BlueprintPersonalization.count() == 2
        BlueprintPersonalization.list().every { it.blueprint.token == other }

        expect:
        service.getDraft(token) == null

        when:
        service.listPublishes(token)

        then:
        thrown(NotFoundException)
    }

    def 'a blueprint the user may not read looks the same as a missing one: #action'() {
        given:
        def token = service.create('Risk', null, BP).token
        service.publish(token, 1)
        loginAs(new TestUser('stranger', ['DESIGNER']))

        expect:
        service.getDraft(token) == null
        service.getPublish(token) == null
        service.getPublish('missing') == null
        service.list() == []

        when:
        call(service, token)

        then:
        def e = thrown(BlueprintNotFoundException)
        e instanceof io.xh.hoist.exception.RoutineException

        when:
        call(service, 'missing')

        then:
        def missing = thrown(BlueprintNotFoundException)
        missing.message == e.message.replace(token, 'missing')

        where:
        action                | call
        'listPublishes'       | { BlueprintService s, String t -> s.listPublishes(t) }
        'saveDraft'           | { BlueprintService s, String t -> s.saveDraft(t, BP, 2, []) }
        'publish'             | { BlueprintService s, String t -> s.publish(t, 2) }
        'copy'                | { BlueprintService s, String t -> s.copy(t, 'Mine') }
        'delete'              | { BlueprintService s, String t -> s.delete(t) }
        'updateAccess'        | { BlueprintService s, String t -> s.updateAccess(t, [], []) }
        'listPersonalizations'| { BlueprintService s, String t -> s.listPersonalizations(t) }
        'savePersonalization' | { BlueprintService s, String t -> s.savePersonalization(t, null, [], 1) }
    }

    def 'personalizations belong to the current user, one per preset'() {
        given:
        def token = service.create('Risk', null, BP, [], ['reader']).token
        service.publish(token, 1)
        def setTheme = [op: 'set', path: '/variables/theme/default', value: 'dark']

        when:
        loginAs(reader)
        service.savePersonalization(token, null, [setTheme], 1)
        service.savePersonalization(token, null, [[op: 'remove', path: '/a']], 1)
        service.savePersonalization(token, 'view:compact', [setTheme], 1)

        then:
        def mine = service.listPersonalizations(token)
        mine.size() == 2
        mine.find { it.preset == BlueprintPersonalization.NO_PRESET }.formatForJSON().preset == null
        parseArray(mine.find { it.preset == BlueprintPersonalization.NO_PRESET }.ops) == [[op: 'remove', path: '/a']]

        when:
        loginAs(owner)

        then:
        service.listPersonalizations(token) == []

        when:
        setConfig(maxPersonalizations: 2)
        loginAs(reader)
        service.savePersonalization(token, 'view:wide', [], 1)

        then:
        def e = thrown(RoutineRuntimeException)
        e.message.contains('at most 2')
    }

    def 'content is checked for structure only: #problem'() {
        given:
        setConfig(maxBytes: 200)
        def token = service.create('Risk', null, BP, [], ['*']).token
        service.publish(token, 1)

        when:
        call(service, token)

        then:
        def e = thrown(RoutineRuntimeException)
        e.message.contains(message)

        where:
        problem                     | call                                                                                           || message
        'a blueprint not an object' | { s, t -> s.saveDraft(t, [1, 2], 1, []) }                                                      || 'must be a JSON object'
        'a forbidden key, nested'   | { s, t -> s.saveDraft(t, [a: [b: ['__proto__': 1]]], 1, []) }                                 || "'__proto__' is forbidden"
        'an oversized blueprint'    | { s, t -> s.saveDraft(t, [text: 'x' * 300], 1, []) }                                          || 'over the limit of 200'
        'a rename of unknown kind'  | { s, t -> s.saveDraft(t, BP, 1, [[kind: 'preset', from: 'a', to: 'b']]) }                     || 'A rename must be'
        'a rename to a forbidden id'| { s, t -> s.saveDraft(t, BP, 1, [[kind: 'element', from: 'a', to: 'constructor']]) }           || "'constructor' is forbidden"
        'an op other than set'      | { s, t -> s.savePersonalization(t, null, [[op: 'addElement', path: '/a']], 1) }               || 'must be {op'
        'a path not a pointer'      | { s, t -> s.savePersonalization(t, null, [[op: 'remove', path: 'a']], 1) }                     || 'must be {op'
        'a set without a value'     | { s, t -> s.savePersonalization(t, null, [[op: 'set', path: '/a']], 1) }                       || 'must be {op'
        'an op with extra keys'     | { s, t -> s.savePersonalization(t, null, [[op: 'remove', path: '/a', x: 1]], 1) }              || 'must be {op'
        'a forbidden path segment'  | { s, t -> s.savePersonalization(t, null, [[op: 'remove', path: '/a/prototype']], 1) }          || "'prototype' is forbidden"
        'a preset not group:item'   | { s, t -> s.savePersonalization(t, 'compact', [], 1) }                                         || "'group:item'"
        'an unknown publish'        | { s, t -> s.savePersonalization(t, null, [], 2) }                                              || 'has no publish 2'
        'grantees not strings'      | { s, t -> s.updateAccess(t, [1], []) }                                                         || 'must be lists of usernames'
    }

    //------------------
    // Implementation
    //------------------
    private void setConfig(Map overrides = [:]) {
        testConfigService.set('xhBlueprintConfig', [enabled: true, createRoles: ['DESIGNER'], *: overrides])
    }

    private String accessAs(TestUser user, String token) {
        loginAs(user)
        info(token).access
    }

    private Map info(String token) {
        service.summaryOf(Blueprint.findByToken(token))
    }
}
