/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.jsonblob

import grails.testing.gorm.DataTest
import grails.testing.services.ServiceUnitTest
import io.xh.hoist.exception.NotAuthorizedException
import io.xh.hoist.test.HoistUnitTest
import io.xh.hoist.test.fakes.TestUser
import spock.lang.Specification

/**
 * Tests {@link JsonBlobService} against an in-memory {@link JsonBlob} table. `alice` is logged
 * in by default; `admin` holds HOIST_ADMIN, the default role for writing global blobs.
 */
class JsonBlobServiceSpec extends Specification implements ServiceUnitTest<JsonBlobService>, DataTest, HoistUnitTest {

    Class[] getDomainClassesToMock() { [JsonBlob] }

    def setup() {
        testConfigService.registerTypedConfig('xhJsonBlobConfig', JsonBlobConfig)
        testUserService.add(new TestUser('admin', ['HOIST_ADMIN']), new TestUser('bob'))
        loginAs('alice')
    }

    //-------------------
    // Create and read
    //-------------------
    def 'create assigns the current user as owner, generates a token and serializes value and meta'() {
        when:
        def blob = service.create(type: 'grid', name: 'mine', value: [a: 1], meta: [group: 'G'], description: 'd')

        then:
        blob.owner == 'alice'
        blob.lastUpdatedBy == 'alice'
        blob.token.size() == 8
        blob.value == '{"a":1}'
        blob.meta == '{"group":"G"}'
        blob.description == 'd'
        blob.archivedDate == 0
    }

    def 'create ignores fields that are not creatable'() {
        when:
        def blob = service.create(type: 'grid', name: 'mine', value: [:], token: 'forced', archivedDate: 99, lastUpdatedBy: 'x')

        then:
        blob.token != 'forced'
        blob.archivedDate == 0
        blob.lastUpdatedBy == 'alice'
    }

    def 'get throws for an unknown or archived token'() {
        given:
        def blob = service.create(type: 'grid', name: 'mine', value: [:])
        service.archive(blob.token)

        when:
        service.get(blob.token)

        then:
        thrown(RuntimeException)

        when:
        service.get('nope')

        then:
        thrown(RuntimeException)
    }

    def 'another user may read a blob only when its acl is the wildcard'() {
        given:
        def mine = service.create(type: 'grid', name: 'private', value: [:])
        def shared = service.create(type: 'grid', name: 'shared', value: [:], acl: '*')

        expect:
        service.get(shared.token, 'bob').is(shared)
        service.find('grid', 'shared', 'alice', 'bob').is(shared)

        when:
        service.get(mine.token, 'bob')

        then:
        thrown(NotAuthorizedException)

        when:
        service.find('grid', 'private', 'alice', 'bob')

        then:
        thrown(NotAuthorizedException)
    }

    //-------------------
    // Listing
    //-------------------
    // Visibility of acl='*' blobs in list() relies on a SQL `like` match that the in-memory test
    // datastore does not reproduce (see docs/testing.md), so these cover the type, owner and
    // archived filters only. Wildcard read access is covered above via get() and find().
    def 'list and listTokens return the user\'s own active blobs of the type'() {
        given:
        def mine = service.create(type: 'grid', name: 'mine', value: [:])
        def mine2 = service.create(type: 'grid', name: 'mine2', value: [:])
        service.create([type: 'grid', name: 'bobs', value: [:]], 'bob')
        service.create(type: 'chart', name: 'other type', value: [:])
        def archived = service.create(type: 'grid', name: 'old', value: [:])
        service.archive(archived.token)

        expect:
        service.list('grid')*.name.toSet() == ['mine', 'mine2'] as Set
        service.listTokens('grid').toSet() == [mine.token, mine2.token] as Set
        service.list('grid', 'bob')*.name == ['bobs']
        service.list('grid', 'carol').isEmpty()
        service.list('chart')*.name == ['other type']
    }

    //-------------------
    // Update and archive
    //-------------------
    def 'the owner may update a blob, and only updatable fields are applied'() {
        given:
        def blob = service.create(type: 'grid', name: 'mine', value: [a: 1])

        when:
        def updated = service.update(blob.token, [name: 'renamed', value: [a: 2], type: 'chart', token: 'x'])

        then:
        updated.is(blob)
        blob.name == 'renamed'
        blob.value == '{"a":2}'
        blob.type == 'grid'
        blob.token != 'x'
    }

    def 'a non-owner cannot update or archive, even with read access'() {
        given:
        def shared = service.create(type: 'grid', name: 'shared', value: [:], acl: '*')

        when:
        service.update(shared.token, [name: 'x'], 'bob')

        then:
        thrown(NotAuthorizedException)

        when:
        service.archive(shared.token, 'bob')

        then:
        thrown(NotAuthorizedException)
    }

    def 'archive soft-deletes and records the authenticated user'() {
        given:
        def blob = service.create(type: 'grid', name: 'mine', value: [:])

        when:
        service.archive(blob.token)

        then:
        blob.archivedDate > 0
        blob.lastUpdatedBy == 'alice'
        service.list('grid').isEmpty()
    }

    def 'a name may be reused once the previous blob is archived'() {
        given:
        def first = service.create(type: 'grid', name: 'mine', value: [:])
        service.archive(first.token)

        when:
        def second = service.create(type: 'grid', name: 'mine', value: [:])

        then:
        second.id != null
        !second.hasErrors()
    }

    def 'a duplicate name for the same type and owner fails validation'() {
        given:
        service.create(type: 'grid', name: 'mine', value: [:])

        when:
        def dup = service.create(type: 'grid', name: 'mine', value: [:])

        then:
        dup == null
    }

    def 'createOrUpdate creates on first call and updates thereafter'() {
        when:
        def created = service.createOrUpdate('grid', 'mine', [value: [a: 1]])
        def updated = service.createOrUpdate('grid', 'mine', [value: [a: 2]])

        then:
        updated.is(created)
        created.value == '{"a":2}'
        service.list('grid').size() == 1
    }

    def 'deleteByNameAndOwner removes matching blobs outright'() {
        given:
        service.create(type: 'grid', name: 'mine', value: [:])
        service.create(type: 'chart', name: 'mine', value: [:])
        service.create([type: 'grid', name: 'mine', value: [:]], 'bob')

        when:
        service.deleteByNameAndOwner('mine', 'alice')

        then:
        JsonBlob.count() == 1
        JsonBlob.list()[0].owner == 'bob'
    }

    //-------------------
    // Global blobs
    //-------------------
    def 'creating a global blob requires a global write role'() {
        when:
        service.create(type: 'grid', name: 'global', value: [:], owner: null)

        then:
        thrown(NotAuthorizedException)

        when:
        def blob = service.create([type: 'grid', name: 'global', value: [:], owner: null], 'admin')

        then:
        blob.owner == null
        blob.lastUpdatedBy == 'admin'
    }

    def 'promoting a blob to global is a write into the global namespace'() {
        given:
        def blob = service.create(type: 'grid', name: 'mine', value: [:])

        when:
        service.update(blob.token, [owner: null])

        then:
        thrown(NotAuthorizedException)
        blob.owner == 'alice'
    }

    def 'canWriteGlobal honors per-type roles, the * fallback and the * role'() {
        given:
        testConfigService.set('xhJsonBlobConfig', [globalWriteRoles: [
            '*'    : ['HOIST_ADMIN'],
            open   : ['*'],
            locked : [],
            special: ['SPECIAL'],
        ]])
        testUserService.add(new TestUser('special', ['SPECIAL']))

        expect:
        service.canWriteGlobal(type, user) == expected

        where:
        type      | user      | expected
        'grid'    | 'admin'   | true
        'grid'    | 'alice'   | false
        'grid'    | 'unknown' | false
        'open'    | 'alice'   | true
        'locked'  | 'admin'   | false
        'special' | 'special' | true
        'special' | 'admin'   | false
    }

    //-------------------
    // Group rename
    //-------------------
    def 'renameGroup rewrites exact and nested group paths within one owner namespace'() {
        given:
        def a = service.create(type: 'grid', name: 'a', value: [:], meta: [group: 'A/B'])
        def b = service.create(type: 'grid', name: 'b', value: [:], meta: [group: 'A/B/x'])
        def c = service.create(type: 'grid', name: 'c', value: [:], meta: [group: 'A/Bee'])
        def d = service.create(type: 'grid', name: 'd', value: [:], meta: [other: 1])
        def bobs = service.create([type: 'grid', name: 'e', value: [:], meta: [group: 'A/B']], 'bob')
        def chart = service.create(type: 'chart', name: 'f', value: [:], meta: [group: 'A/B'])

        when:
        def count = service.renameGroup('grid', 'alice', 'A/B', 'A/C')

        then:
        count == 2
        a.meta == '{"group":"A/C"}'
        b.meta == '{"group":"A/C/x"}'
        c.meta == '{"group":"A/Bee"}'
        d.meta == '{"other":1}'
        bobs.meta == '{"group":"A/B"}'
        chart.meta == '{"group":"A/B"}'
    }

    def 'renameGroup requires both paths and write access to the namespace'() {
        when:
        service.renameGroup('grid', 'alice', ' ', 'B')

        then:
        thrown(IllegalArgumentException)

        when:
        service.renameGroup('grid', 'bob', 'A', 'B')

        then:
        thrown(NotAuthorizedException)

        when:
        service.renameGroup('grid', null, 'A', 'B')

        then:
        thrown(NotAuthorizedException)
    }
}
