/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.view

import grails.testing.gorm.DataTest
import grails.testing.services.ServiceUnitTest
import io.xh.hoist.exception.NotAuthorizedException
import io.xh.hoist.jsonblob.JsonBlob
import io.xh.hoist.jsonblob.JsonBlobConfig
import io.xh.hoist.jsonblob.JsonBlobService
import io.xh.hoist.test.HoistUnitTest
import io.xh.hoist.test.fakes.TestUser
import spock.lang.Specification


/**
 * Tests {@link ViewService} over a {@link JsonBlobService} backed by an in-memory {@link JsonBlob}
 * table. `alice` is logged in; `admin` holds HOIST_ADMIN and so may manage global views.
 */
class ViewServiceSpec extends Specification implements ServiceUnitTest<ViewService>, DataTest, HoistUnitTest {

    static final String TYPE = 'portfolioGrid'

    Class[] getDomainClassesToMock() { [JsonBlob] }

    JsonBlobService blobs

    def setup() {
        testConfigService.registerTypedConfig('xhJsonBlobConfig', JsonBlobConfig)
        blobs = defineService(JsonBlobService)
        testUserService.add(new TestUser('admin', ['HOIST_ADMIN']), new TestUser('bob'))
        loginAs('alice')
    }

    //-------------------
    // Create
    //-------------------
    def 'a private view is owned by the user and not shared'() {
        when:
        def view = service.create(type: TYPE, name: 'Mine', description: 'd', group: 'G', value: [cols: 1])

        then:
        view.owner == 'alice'
        view.acl == null
        view.meta == [group: 'G', isShared: null]
        view.value == [cols: 1]
        view.token
        with(testTrackService.lastTracked) {
            category == 'Views'
            msg == 'Created View'
            data == [name: 'Mine', token: view.token, isGlobal: false]
        }
    }

    def 'a shared view keeps its owner but is readable by all'() {
        when:
        def view = service.create(type: TYPE, name: 'Shared', group: 'G', isShared: true, value: [:])

        then:
        view.owner == 'alice'
        view.acl == '*'
        view.meta == [group: 'G', isShared: true]
        service.get(view.token, 'bob').name == 'Shared'
    }

    def 'a global view has no owner and requires the global write role'() {
        when:
        service.create(type: TYPE, name: 'Global', isGlobal: true, value: [:])

        then:
        thrown(NotAuthorizedException)

        when:
        def view = service.create([type: TYPE, name: 'Global', group: 'G', isGlobal: true, value: [:]], 'admin')

        then:
        view.owner == null
        view.acl == '*'
        view.meta == [group: 'G']
        testTrackService.lastTracked.data.isGlobal == true
    }

    def 'creating with isPinned records the pin in the user state'() {
        when:
        def view = service.create(type: TYPE, name: 'Mine', isPinned: true, value: [:])

        then:
        service.getAllData(TYPE, 'default').state.userPinned == [(view.token): true]
    }

    //-------------------
    // All data and state
    //-------------------
    // Listing of views shared by other users depends on a SQL `like` match that the in-memory
    // datastore does not reproduce (see docs/testing.md), so this covers the user's own views.
    def 'getAllData returns the user\'s views without values, the user state, and global management rights'() {
        given:
        def mine = service.create(type: TYPE, name: 'Mine', value: [big: 1])
        def shared = service.create(type: TYPE, name: 'Shared', isShared: true, value: [:])
        service.create([type: TYPE, name: 'Bobs', value: [:]], 'bob')
        service.create(type: 'otherType', name: 'Other', value: [:])
        service.updateState(TYPE, 'main', [currentView: mine.token, autoSave: true])

        when:
        def data = service.getAllData(TYPE, 'main')

        then:
        data.views*.name.toSet() == ['Mine', 'Shared'] as Set
        data.views.every { !it.containsKey('value') }
        data.state == [userPinned: [:], autoSave: true, currentView: mine.token]
        data.manageGlobal == false
        service.getAllData(TYPE, 'main', 'admin').manageGlobal == true
    }

    def 'state is kept per view instance, and pins are pruned to existing views'() {
        given:
        def a = service.create(type: TYPE, name: 'A', value: [:])
        def b = service.create(type: TYPE, name: 'B', value: [:])

        when:
        service.updateState(TYPE, 'left', [currentView: a.token])
        def state = service.updateState(TYPE, 'right', [currentView: b.token, userPinned: [(a.token): true, (b.token): false, ghost: true]])

        then:
        state == [userPinned: [(a.token): true, (b.token): false], autoSave: false, currentView: b.token]
        service.getAllData(TYPE, 'left').state.currentView == a.token
        !service.getAllData(TYPE, 'other').state.containsKey('currentView')

        when: 'pins merge with earlier pins'
        state = service.updateState(TYPE, 'right', [userPinned: [(b.token): true]])

        then:
        state.userPinned == [(a.token): true, (b.token): true]
    }

    def 'clearAllState removes the user state blob only'() {
        given:
        service.create(type: TYPE, name: 'A', value: [:])
        service.updateState(TYPE, 'main', [autoSave: true])

        when:
        service.clearAllState()

        then:
        JsonBlob.list()*.name == ['A']
    }

    //-------------------
    // Update
    //-------------------
    def 'updateInfo changes core fields and group, and tracks'() {
        given:
        def view = service.create(type: TYPE, name: 'Old', description: 'd', group: 'G1', value: [:])

        when:
        def updated = service.updateInfo(view.token, [name: 'New', description: 'd2', group: 'G2', isPinned: true])

        then:
        updated.name == 'New'
        updated.description == 'd2'
        updated.meta == [group: 'G2', isShared: null]
        service.getAllData(TYPE, 'default').state.userPinned == [(view.token): true]
        testTrackService.lastTracked.msg == 'Updated View Info'
    }

    def 'sharing, unsharing and promoting to global adjust owner, acl and meta'() {
        given:
        def view = service.create(type: TYPE, name: 'V', group: 'G', value: [:])

        when:
        def shared = service.updateInfo(view.token, [isShared: true])

        then:
        shared.owner == 'alice'
        shared.acl == '*'
        shared.meta == [group: 'G', isShared: true]

        when:
        def unshared = service.updateInfo(view.token, [isShared: false])

        then:
        unshared.owner == 'alice'
        unshared.acl == null
        unshared.meta == [group: 'G', isShared: false]

        when: 'the owner cannot promote to global without the global write role'
        service.updateInfo(view.token, [isGlobal: true])

        then:
        thrown(NotAuthorizedException)

        when: 'granted the role, the owner can'
        testUserService.add(new TestUser('alice', ['HOIST_ADMIN']))
        service.updateInfo(view.token, [isShared: true])
        def global = service.updateInfo(view.token, [isGlobal: true])

        then:
        global.owner == null
        global.acl == '*'
        global.meta == [group: 'G']
        testTrackService.lastTracked.data.isGlobal == true
    }

    def 'bulkUpdateInfo applies to every view it can, pins once per type, then reports failures'() {
        given:
        def a = service.create(type: TYPE, name: 'A', value: [:])
        def b = service.create(type: 'otherType', name: 'B', value: [:])
        def bobs = service.create([type: TYPE, name: 'Bobs', value: [:]], 'bob')
        testTrackService.clear()

        when:
        service.bulkUpdateInfo([a.token, b.token, bobs.token], [group: 'Moved', isPinned: true])

        then:
        def e = thrown(RuntimeException)
        e.message.contains('Failed to update 1 of 3')
        e.message.contains(bobs.token)

        and:
        service.get(a.token).meta.group == 'Moved'
        service.get(b.token).meta.group == 'Moved'
        service.getAllData(TYPE, 'default').state.userPinned == [(a.token): true]
        service.getAllData('otherType', 'default').state.userPinned == [(b.token): true]
        testTrackService.tracked*.msg == ['Bulk Updated View Info']
        testTrackService.lastTracked.data == [count: 2]
    }

    def 'updateValue replaces the value and tracks only for global views'() {
        given:
        def mine = service.create(type: TYPE, name: 'Mine', value: [v: 1])
        def global = service.create([type: TYPE, name: 'Global', isGlobal: true, value: [v: 1]], 'admin')
        testTrackService.clear()

        when:
        def updated = service.updateValue(mine.token, [v: 2])

        then:
        updated.value == [v: 2]
        testTrackService.tracked.isEmpty()

        when:
        service.updateValue(global.token, [v: 3], 'admin')

        then:
        testTrackService.lastTracked.msg == 'Updated Global View definition'
    }

    //-------------------
    // Delete and groups
    //-------------------
    def 'delete archives the views it can and reports the rest'() {
        given:
        def a = service.create(type: TYPE, name: 'A', value: [:])
        def bobs = service.create([type: TYPE, name: 'Bobs', value: [:]], 'bob')
        testTrackService.clear()

        when:
        service.delete([a.token, bobs.token])

        then:
        def e = thrown(RuntimeException)
        e.message.contains('Failed to delete 1 view(s)')
        service.getAllData(TYPE, 'default').views.isEmpty()
        service.getAllData(TYPE, 'default', 'bob').views*.name == ['Bobs']
        testTrackService.lastTracked.msg == 'Deleted Views'
        testTrackService.lastTracked.data == [count: 1]
    }

    def 'renameGroup cascades through the user\'s own views and tracks the count'() {
        given:
        def a = service.create(type: TYPE, name: 'A', group: 'Reports/Sales', value: [:])
        def b = service.create(type: TYPE, name: 'B', group: 'Reports/Sales/Monthly', value: [:])
        def global = service.create([type: TYPE, name: 'G', group: 'Reports/Sales', isGlobal: true, value: [:]], 'admin')
        testTrackService.clear()

        when:
        def result = service.renameGroup(TYPE, 'Reports/Sales', 'Archive/Sales', false)

        then:
        result == [count: 2]
        service.get(a.token).meta.group == 'Archive/Sales'
        service.get(b.token).meta.group == 'Archive/Sales/Monthly'
        service.get(global.token).meta.group == 'Reports/Sales'
        testTrackService.lastTracked.data == [type: TYPE, from: 'Reports/Sales', to: 'Archive/Sales', isGlobal: false, count: 2]
    }
}
