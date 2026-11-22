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

    static final String T = 'test.app'

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
        service.list(T)

        then:
        def e = thrown(RoutineRuntimeException)
        e.message.contains('not enabled')
    }

    def 'creating needs a create role, and the creator owns the blueprint'() {
        when:
        def bp = service.create(T, 'Risk', 'Desk risk', BP)

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
        service.create(T, 'Mine', null, BP)

        then:
        thrown(NotAuthorizedException)
    }

    def 'a draft save needs the current base, and a stale one changes nothing'() {
        given:
        def token = service.create(T, 'Risk', null, BP).token

        expect:
        service.saveDraft(T, token, [*: BP, title: 'v2'], 1, [RENAME]) == 2

        when:
        service.saveDraft(T, token, [*: BP, title: 'stale'], 1, [])

        then:
        def e = thrown(BlueprintStaleBaseException)
        e.base == 1
        e.current == 2

        and:
        def draft = service.getDraft(T, token)
        draft.draftVersion == 2
        parseObject(draft.draft).title == 'v2'
        parseArray(draft.pendingRenames) == [RENAME]
    }

    def 'each publish takes the next number with the pending renames, and bumps the draft version'() {
        given:
        def token = service.create(T, 'Risk', null, BP).token
        service.saveDraft(T, token, BP, 1, [RENAME])

        when:
        def first = service.publish(T, token, 2, 'First')

        then:
        first.number == 1
        first.note == 'First'
        parseArray(first.renames) == [RENAME]
        first.blueprint.draftVersion == 3

        when: 'a save based on the draft from before the publish'
        service.saveDraft(T, token, BP, 2, [RENAME])

        then: 'is refused, rather than re-adding the published rename'
        thrown(BlueprintStaleBaseException)

        when:
        def second = service.publish(T, token, 3)

        then:
        second.number == 2
        parseArray(second.renames) == []

        and:
        def draft = service.getDraft(T, token)
        draft.draftVersion == 4
        draft.publishedVersion == 2
        draft.pendingRenames == '[]'
        parseObject(draft.draft) == BP
        service.listPublishes(T, token)*.number == [1, 2]
        service.listPublishes(T, token)[0].renames == [RENAME]

        when:
        service.publish(T, token, 3)

        then:
        thrown(BlueprintStaleBaseException)
    }

    def 'readers see only the latest publish, and only once there is one'() {
        given:
        def token = service.create(T, 'Risk', null, BP, [], ['reader']).token

        when:
        loginAs(reader)

        then:
        service.list(T) == []
        info(token).subMap('access', 'canPublish', 'canManage') == [access: 'read', canPublish: false, canManage: false]
        !info(token).containsKey('writers') && !info(token).containsKey('readers')
        service.getPublish(T, token) == null

        when:
        loginAs(owner)
        service.publish(T, token, 1)
        service.saveDraft(T, token, [*: BP, title: 'later'], 2, [])
        service.publish(T, token, 3)
        loginAs(reader)

        then:
        service.list(T)*.token == [token]
        service.list(T)[0].access == 'read'
        service.getPublish(T, token).number == 2
        service.getDraft(T, token) == null
        service.getPublish(T, token, 1) == null

        when:
        call(service)

        then:
        thrown(NotAuthorizedException)

        where:
        call << [
            { BlueprintService s -> s.listPublishes(T, s.list(T)[0].token as String) },
            { BlueprintService s -> s.publish(T, s.list(T)[0].token as String, 2) },
            { BlueprintService s -> s.saveDraft(T, s.list(T)[0].token as String, BP, 2, []) }
        ]
    }

    def 'grantees can be usernames, roles or everyone, and admins have write everywhere'() {
        given:
        def token = service.create(T, 'Risk', null, BP, ['role:EDITOR'], ['*']).token

        expect:
        accessAs(editor, token) == 'write'
        accessAs(stranger, token) == 'read'
        accessAs(admin, token) == 'write'
        accessAs(owner, token) == 'write'
    }

    def 'only the owner or an admin may manage access or delete'() {
        given:
        def token = service.create(T, 'Risk', null, BP, ['editor'], []).token

        when:
        loginAs(editor)
        service.updateAccess(T, token, [], [])

        then:
        thrown(NotAuthorizedException)

        when:
        service.delete(T, token)

        then:
        thrown(NotAuthorizedException)

        when:
        loginAs(admin)
        def bp = service.updateAccess(T, token, ['designer'], ['role:EDITOR'])

        then:
        parseArray(bp.writers) == ['designer']
        accessAs(editor, token) == 'read'
    }

    def 'an impersonating admin may view and edit the draft, but not #action'() {
        given:
        def token = service.create(T, 'Risk', null, BP).token
        loginAs(admin)
        impersonate('owner', 'admin')

        expect: 'the impersonated user\'s access, for viewing and draft edits'
        service.getDraft(T, token).owner == 'owner'
        service.saveDraft(T, token, BP, 1, []) == 2

        when:
        call(service, token)

        then:
        def e = thrown(NotAuthorizedException)
        e.message == "Blueprints cannot be $past while impersonating another user. Stop impersonating to $action this blueprint."

        where:
        action    | past        | call
        'publish' | 'published' | { BlueprintService s, String t -> s.publish(T, t, 2) }
        'share'   | 'shared'    | { BlueprintService s, String t -> s.updateAccess(T, t, ['editor'], []) }
        'delete'  | 'deleted'   | { BlueprintService s, String t -> s.delete(T, t) }
        'rename'  | 'renamed'   | { BlueprintService s, String t -> s.rename(T, t, [name: 'Renamed']) }
    }

    def 'a writer may rename a blueprint, and its description changes only when given'() {
        given:
        def token = service.create(T, 'Risk', 'Desk risk', BP, ['editor'], ['reader']).token

        when:
        def renamed = service.rename(T, token, [name: 'Desk risk'])

        then:
        [renamed.name, renamed.description, renamed.draftVersion, renamed.lastUpdatedBy] == ['Desk risk', 'Desk risk', 1L, 'owner']

        when:
        loginAs(editor)
        renamed = service.rename(T, token, [name: 'Book risk', description: null])

        then:
        [renamed.name, renamed.description, renamed.lastUpdatedBy] == ['Book risk', null, 'editor']

        when:
        loginAs(reader)
        service.rename(T, token, [name: 'Mine now'])

        then:
        thrown(NotAuthorizedException)

        when:
        service.rename('other.app', token, [name: 'Elsewhere'])

        then:
        thrown(BlueprintNotFoundException)
    }

    def 'a rename keeps names unique for the blueprint\'s owner and type'() {
        given:
        def risk = service.create(T, 'Risk', null, BP, ['editor'], []).token
        service.create(T, 'Book risk', null, BP)
        service.create('other.app', 'Other', null, BP)

        expect: 'its own name, or a name the owner uses only in another type'
        service.rename(T, risk, [name: 'Risk']).name == 'Risk'
        service.rename(T, risk, [name: 'Other']).name == 'Other'

        when:
        service.rename(T, risk, [name: 'Book risk'])

        then:
        def own = thrown(RoutineRuntimeException)
        own.message == "You already have a blueprint named 'Book risk'"

        when: 'a writer renames to a name its owner already uses'
        loginAs(editor)
        service.rename(T, risk, [name: 'Book risk'])

        then:
        def other = thrown(RoutineRuntimeException)
        other.message == "Its owner already has a blueprint named 'Book risk'"

        when:
        service.rename(T, risk, [name: name])

        then:
        thrown(RoutineRuntimeException)

        where:
        name << [null, '', '  ', 'x' * 256]
    }

    def 'sharing with everyone needs globalShareRoles to add it, but not to keep or remove it'() {
        given:
        setConfig(globalShareRoles: ['HOIST_ADMIN'])

        when:
        service.create(T, 'Mine', null, BP, [], ['*'])

        then:
        def e = thrown(NotAuthorizedException)
        e.message.contains('globalShareRoles')

        when:
        def token = service.create(T, 'Mine', null, BP, ['editor'], ['role:EDITOR']).token

        then:
        !info(token).canShareGlobally

        when:
        service.updateAccess(T, token, [], ['*'])

        then:
        thrown(NotAuthorizedException)

        when: 'an admin shares it with everyone'
        loginAs(admin)
        service.updateAccess(T, token, [], ['*'])

        then:
        info(token).canShareGlobally

        when: 'its owner keeps everyone while changing other grants, then removes everyone'
        loginAs(owner)
        service.updateAccess(T, token, ['editor'], ['*'])
        def removed = service.updateAccess(T, token, ['editor'], [])

        then:
        parseArray(removed.readers) == []

        when: 'globalShareRoles includes the owner\'s role'
        setConfig(globalShareRoles: ['DESIGNER'])

        then:
        info(token).canShareGlobally
        parseArray(service.updateAccess(T, token, [], ['*']).readers) == ['*']

        when: 'everyone as a writer, even for an admin'
        loginAs(admin)
        service.updateAccess(T, token, ['*'], [])

        then:
        def writers = thrown(RoutineRuntimeException)
        writers.message.contains("'*' may only be a reader")

        when:
        loginAs(owner)
        service.create(T, 'Open', null, BP, ['*'], [])

        then:
        thrown(RoutineRuntimeException)
    }

    def 'ensureGlobal creates and publishes a global blueprint once, with no user checks'() {
        given:
        logout()

        when:
        def first = service.ensureGlobal(T, 'p1', 'P1. One grid', null, BP, 'toolbox')
        def again = service.ensureGlobal(T, 'p1', 'P1. One grid', null, BP, 'toolbox')

        then:
        again.token == first.token
        Blueprint.count() == 1
        BlueprintPublish.count() == 1
        first.owner == 'toolbox'
        parseArray(first.readers) == ['*']
        parseArray(first.writers) == []
        [first.draftVersion, first.publishedVersion] == [2L, 1]
        parseObject(BlueprintPublish.findByBlueprint(first).content) == BP

        when: 'any user reads its latest publish, but cannot edit it'
        loginAs(stranger)

        then:
        service.list(T)*.token == [first.token]
        info(first.token).subMap('access', 'canManage') == [access: 'read', canManage: false]
        service.getPublish(T, first.token).number == 1
        service.getDraft(T, first.token) == null
    }

    def 'ensureGlobal republishes a global blueprint whose latest publish differs, as its owner'() {
        given:
        logout()
        def token = service.ensureGlobal(T, 'p1', 'P1. One grid', null, BP, 'toolbox').token
        Map changed = [*: BP, title: 'changed']

        when:
        def ret = service.ensureGlobal(T, 'p1', 'P1. One grid', null, changed, 'toolbox')
        service.ensureGlobal(T, 'p1', 'P1. One grid', null, changed, 'toolbox')

        then:
        ret.token == token
        BlueprintPublish.count() == 2
        [ret.draftVersion, ret.publishedVersion] == [4L, 2]
        parseObject(ret.draft) == changed
        ret.lastUpdatedBy == 'toolbox'

        when:
        def latest = BlueprintPublish.findByBlueprintAndNumber(ret, 2)

        then:
        parseObject(latest.content) == changed
        latest.publishedBy == 'toolbox'
        parseArray(latest.renames) == []
    }

    def 'ensureGlobal replaces an admin\'s edits to a global blueprint, keeping its sharing'() {
        given:
        def token = service.ensureGlobal(T, 'p1', 'P1. One grid', null, BP, 'toolbox').token
        loginAs(admin)
        service.saveDraft(T, token, [*: BP, title: 'edited'], 2, [RENAME])
        service.publish(T, token, 3)
        service.saveDraft(T, token, [*: BP, title: 'unpublished'], 4, [])
        service.updateAccess(T, token, ['designer'], ['*'])

        when:
        def ret = service.ensureGlobal(T, 'p1', 'P1. One grid', null, BP, 'toolbox')

        then:
        [ret.draftVersion, ret.publishedVersion] == [7L, 3]
        parseObject(ret.draft) == BP
        parseArray(ret.pendingRenames) == []
        parseObject(service.getPublish(T, token).content) == BP
        parseArray(ret.writers) == ['designer']
    }

    def 'ensureGlobal keeps its token across names, and refuses a token of another type or owner'() {
        given:
        logout()
        def first = service.ensureGlobal(T, 'portfolio-overview', 'S1. Overview', null, BP, 'toolbox')

        when:
        def renamed = service.ensureGlobal(T, 'portfolio-overview', 'S1. Portfolio overview', null, BP, 'toolbox')

        then:
        first.token == 'portfolio-overview'
        renamed.id == first.id
        Blueprint.count() == 1
        BlueprintPublish.count() == 1
        Blueprint.findByToken('portfolio-overview').name == 'S1. Portfolio overview'

        when: 'a new description, with the content unchanged'
        service.ensureGlobal(T, 'portfolio-overview', 'S1. Portfolio overview', 'Positions, P&L and risk for a fund.', BP, 'toolbox')

        then:
        Blueprint.findByToken('portfolio-overview').description == 'Positions, P&L and risk for a fund.'
        BlueprintPublish.count() == 1

        when:
        service.ensureGlobal(type, 'portfolio-overview', 'S1', null, BP, seeder)

        then:
        def e = thrown(RoutineRuntimeException)
        e.message == "Blueprint token 'portfolio-overview' is taken by a blueprint of another type or owner"

        where:
        type        | seeder
        'other.app' | 'toolbox'
        T           | 'someone'
    }

    def 'ensureGlobal takes only a URL-safe token'() {
        when:
        service.ensureGlobal(T, token, 'S1', null, BP, 'toolbox')

        then:
        def e = thrown(RoutineRuntimeException)
        e.message.startsWith('A global blueprint token must be')

        where:
        token << ['', 'has space', 'a/b', 'x' * 65, null]
    }

    def 'ensureGlobal still needs storage enabled, and checks content structure'() {
        when:
        setConfig(enabled: false)
        service.ensureGlobal(T, 'p1', 'P1', null, BP, 'toolbox')

        then:
        def disabled = thrown(RoutineRuntimeException)
        disabled.message.contains('not enabled')

        when:
        setConfig()
        service.ensureGlobal(T, 'p1', 'P1', null, [a: ['__proto__': 1]], 'toolbox')

        then:
        def forbidden = thrown(RoutineRuntimeException)
        forbidden.message.contains("'__proto__' is forbidden")
    }

    def 'a type scopes every call, so a blueprint of another type looks missing'() {
        given:
        def token = service.create(T, 'Risk', null, BP, [], ['*']).token
        service.publish(T, token, 1)
        String other = 'other.app'

        expect:
        service.list(other) == []
        service.getDraft(other, token) == null
        service.getPublish(other, token) == null
        service.list(T)*.type == [T]

        when:
        service."$method"(other, token, *args)

        then:
        thrown(BlueprintNotFoundException)

        when: 'a copiedFrom of another type'
        service.create(other, 'Copy', null, BP, [], [], [id: token, version: 1, as: 'publish'])

        then:
        thrown(BlueprintNotFoundException)

        where:
        method                 | args
        'saveDraft'            | [BP, 2L, []]
        'publish'              | [2L]
        'listPublishes'        | []
        'copy'                 | ['Copy']
        'updateAccess'         | [[], []]
        'delete'               | []
        'listPersonalizations' | []
        'savePersonalization'  | [null, [], 1]
    }

    def 'names are unique per owner and type'() {
        given:
        def token = service.create(T, 'Risk', null, BP).token
        service.publish(T, token, 1)

        when:
        service.create(T, 'Risk', null, BP)

        then:
        def e = thrown(RoutineRuntimeException)
        e.message == "You already have a blueprint named 'Risk'"

        when:
        service.copy(T, token, 'Risk')

        then:
        thrown(RoutineRuntimeException)

        when: 'another type, or another owner'
        service.create('other.app', 'Risk', null, BP)
        service.updateAccess(T, token, [], ['designer'])
        loginAs(designer)
        service.copy(T, token, 'Risk')

        then:
        Blueprint.countByName('Risk') == 3
    }

    def 'a type must be a non-blank string of at most 50 characters'() {
        when:
        service.list(type)

        then:
        def e = thrown(RoutineRuntimeException)
        e.message.startsWith('A blueprint type must be')

        where:
        type << [null, '', '  ', 'x' * 51]
    }

    def 'publishRoles narrow publishing for writers, but not for admins'() {
        given:
        setConfig(publishRoles: ['PUBLISHER'])
        def token = service.create(T, 'Risk', null, BP).token

        when:
        service.publish(T, token, 1)

        then:
        thrown(NotAuthorizedException)
        !info(token).canPublish

        when:
        loginAs(admin)

        then:
        service.publish(T, token, 1).number == 1
    }

    def 'a copy takes the latest publish by default, records its source, and needs a create role'() {
        given:
        def token = service.create(T, 'Risk', 'Desk risk', BP, [], ['designer']).token

        expect: 'nothing to copy before a publish'
        service.copy(T, token, 'Copy') == null

        when:
        service.saveDraft(T, token, [*: BP, title: 'published'], 1, [])
        service.publish(T, token, 2)
        loginAs(designer)
        def copy = service.copy(T, token, 'My copy')

        then:
        copy.owner == 'designer'
        copy.name == 'My copy'
        copy.description == 'Desk risk'
        copy.draftVersion == 1
        copy.publishedVersion == null
        parseObject(copy.draft).title == 'published'
        [copy.copiedFromToken, copy.copiedFromVersion, copy.copiedFromKind] == [token, 1L, 'publish']

        when: 'a reader copying the draft'
        service.copy(T, token, 'Draft copy', 'draft')

        then:
        thrown(NotAuthorizedException)

        when: 'a reader without a create role'
        loginAs(reader)
        service.copy(T, token, 'No')

        then:
        thrown(NotAuthorizedException)
    }

    def 'create records a source the user may open: #source'() {
        given: 'a blueprint at draft version 3, with publishes 1 and 2, which designer may read'
        def token = service.create(T, 'Risk', null, BP, [], ['designer']).token
        service.publish(T, token, 1)
        service.publish(T, token, 2)
        loginAs(user == 'owner' ? owner : designer)

        when:
        def copy = service.create(T, 'Saved', null, BP, [], [], [id: token, version: version, as: kind])

        then:
        [copy.copiedFromToken, copy.copiedFromVersion, copy.copiedFromKind] == [token, version as Long, kind]
        copy.owner == user

        where:
        source                              | user       | kind      | version
        'a writer\'s stale draft version'   | 'owner'    | 'draft'   | 2
        'a writer\'s current draft'         | 'owner'    | 'draft'   | 3
        'a writer\'s older publish'         | 'owner'    | 'publish' | 1
        'a reader\'s latest publish'        | 'designer' | 'publish' | 2
    }

    def 'create refuses a source the user may not open: #problem'() {
        given:
        def token = service.create(T, 'Risk', null, BP, [], ['designer']).token
        service.publish(T, token, 1)
        service.publish(T, token, 2)
        def hidden = service.create(T, 'Hidden', null, BP).token
        loginAs(user == 'owner' ? owner : designer)

        when:
        service.create(T, 'Saved', null, BP, [], [], copiedFrom(token, hidden))

        then:
        def e = thrown(error)
        e.message.contains(message)

        where:
        problem                        | user       | copiedFrom                                                    | error                      || message
        'a reader citing the draft'    | 'designer' | { t, h -> [id: t, version: 3, as: 'draft'] }                  | NotAuthorizedException     || 'does not have write access'
        'a reader citing an old publish'| 'designer' | { t, h -> [id: t, version: 1, as: 'publish'] }               | NotAuthorizedException     || 'does not have write access'
        'an unreadable source'         | 'designer' | { t, h -> [id: h, version: 1, as: 'draft'] }                  | BlueprintNotFoundException || 'not found'
        'a missing source'             | 'owner'    | { t, h -> [id: 'nope', version: 1, as: 'draft'] }             | BlueprintNotFoundException || 'not found'
        'a draft version ahead'        | 'owner'    | { t, h -> [id: t, version: 9, as: 'draft'] }                  | RoutineRuntimeException    || 'has no draft version 9'
        'a publish that does not exist'| 'owner'    | { t, h -> [id: t, version: 9, as: 'publish'] }                | RoutineRuntimeException    || 'has no publish 9'
        'a malformed source'           | 'owner'    | { t, h -> [id: t, version: '1', as: 'draft'] }                | RoutineRuntimeException    || 'copiedFrom must be {id, version, as}'
        'an unknown kind'              | 'owner'    | { t, h -> [id: t, version: 1, as: 'preset'] }                 | RoutineRuntimeException    || 'copiedFrom must be {id, version, as}'
    }

    def 'deleting a blueprint deletes its publishes and every user\'s personalizations'() {
        given:
        def token = service.create(T, 'Risk', null, BP, [], ['*']).token
        def other = service.create(T, 'Other', null, BP, [], ['*']).token
        [token, other].each { service.publish(T, it, 1) }
        [owner, reader].each { user ->
            loginAs(user)
            [token, other].each { service.savePersonalization(T, it, null, [], 1) }
        }
        loginAs(owner)

        when:
        service.delete(T, token)

        then:
        !Blueprint.findByToken(token)
        BlueprintPublish.count() == 1
        BlueprintPersonalization.count() == 2
        BlueprintPersonalization.list().every { it.blueprint.token == other }

        expect:
        service.getDraft(T, token) == null

        when:
        service.listPublishes(T, token)

        then:
        thrown(NotFoundException)
    }

    def 'a blueprint the user may not read looks the same as a missing one: #action'() {
        given:
        def token = service.create(T, 'Risk', null, BP).token
        service.publish(T, token, 1)
        loginAs(new TestUser('stranger', ['DESIGNER']))

        expect:
        service.getDraft(T, token) == null
        service.getPublish(T, token) == null
        service.getPublish(T, 'missing') == null
        service.list(T) == []

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
        'listPublishes'       | { BlueprintService s, String t -> s.listPublishes(T, t) }
        'saveDraft'           | { BlueprintService s, String t -> s.saveDraft(T, t, BP, 2, []) }
        'publish'             | { BlueprintService s, String t -> s.publish(T, t, 2) }
        'copy'                | { BlueprintService s, String t -> s.copy(T, t, 'Mine') }
        'delete'              | { BlueprintService s, String t -> s.delete(T, t) }
        'updateAccess'        | { BlueprintService s, String t -> s.updateAccess(T, t, [], []) }
        'listPersonalizations'| { BlueprintService s, String t -> s.listPersonalizations(T, t) }
        'savePersonalization' | { BlueprintService s, String t -> s.savePersonalization(T, t, null, [], 1) }
    }

    def 'personalizations belong to the current user, one per preset'() {
        given:
        def token = service.create(T, 'Risk', null, BP, [], ['reader']).token
        service.publish(T, token, 1)
        def setTheme = [op: 'set', path: '/variables/theme/default', value: 'dark']

        when:
        loginAs(reader)
        service.savePersonalization(T, token, null, [setTheme], 1)
        service.savePersonalization(T, token, null, [[op: 'remove', path: '/a']], 1)
        service.savePersonalization(T, token, 'view:compact', [setTheme], 1)

        then:
        def mine = service.listPersonalizations(T, token)
        mine.size() == 2
        mine.find { it.preset == BlueprintPersonalization.NO_PRESET }.formatForJSON().preset == null
        parseArray(mine.find { it.preset == BlueprintPersonalization.NO_PRESET }.ops) == [[op: 'remove', path: '/a']]

        when:
        loginAs(owner)

        then:
        service.listPersonalizations(T, token) == []

        when:
        setConfig(maxPersonalizations: 2)
        loginAs(reader)
        service.savePersonalization(T, token, 'view:wide', [], 1)

        then:
        def e = thrown(RoutineRuntimeException)
        e.message.contains('at most 2')
    }

    def 'content is checked for structure only: #problem'() {
        given:
        setConfig(maxBytes: 200)
        def token = service.create(T, 'Risk', null, BP, [], ['*']).token
        service.publish(T, token, 1)

        when:
        call(service, token)

        then:
        def e = thrown(RoutineRuntimeException)
        e.message.contains(message)

        where:
        problem                     | call                                                                                           || message
        'a blueprint not an object' | { s, t -> s.saveDraft(T, t, [1, 2], 1, []) }                                                      || 'must be a JSON object'
        'a forbidden key, nested'   | { s, t -> s.saveDraft(T, t, [a: [b: ['__proto__': 1]]], 1, []) }                                 || "'__proto__' is forbidden"
        'an oversized blueprint'    | { s, t -> s.saveDraft(T, t, [text: 'x' * 300], 1, []) }                                          || 'over the limit of 200'
        'a rename of unknown kind'  | { s, t -> s.saveDraft(T, t, BP, 1, [[kind: 'preset', from: 'a', to: 'b']]) }                     || 'A rename must be'
        'a rename to a forbidden id'| { s, t -> s.saveDraft(T, t, BP, 1, [[kind: 'element', from: 'a', to: 'constructor']]) }           || "'constructor' is forbidden"
        'an op other than set'      | { s, t -> s.savePersonalization(T, t, null, [[op: 'addElement', path: '/a']], 1) }               || 'must be {op'
        'a path not a pointer'      | { s, t -> s.savePersonalization(T, t, null, [[op: 'remove', path: 'a']], 1) }                     || 'must be {op'
        'a set without a value'     | { s, t -> s.savePersonalization(T, t, null, [[op: 'set', path: '/a']], 1) }                       || 'must be {op'
        'an op with extra keys'     | { s, t -> s.savePersonalization(T, t, null, [[op: 'remove', path: '/a', x: 1]], 1) }              || 'must be {op'
        'a forbidden path segment'  | { s, t -> s.savePersonalization(T, t, null, [[op: 'remove', path: '/a/prototype']], 1) }          || "'prototype' is forbidden"
        'a preset not group:item'   | { s, t -> s.savePersonalization(T, t, 'compact', [], 1) }                                         || "'group:item'"
        'an unknown publish'        | { s, t -> s.savePersonalization(T, t, null, [], 2) }                                              || 'has no publish 2'
        'grantees not strings'      | { s, t -> s.updateAccess(T, t, [1], []) }                                                         || 'must be lists of usernames'
    }

    //------------------
    // Implementation
    //------------------
    private void setConfig(Map overrides = [:]) {
        testConfigService.set('xhBlueprintConfig', [enabled: true, createRoles: ['DESIGNER'], globalShareRoles: ['DESIGNER'], *: overrides])
    }

    private String accessAs(TestUser user, String token) {
        loginAs(user)
        info(token).access
    }

    private Map info(String token) {
        service.summaryOf(Blueprint.findByToken(token))
    }
}
