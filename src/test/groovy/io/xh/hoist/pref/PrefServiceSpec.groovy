/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.pref

import grails.testing.gorm.DataTest
import grails.testing.services.ServiceUnitTest
import io.xh.hoist.test.HoistUnitTest
import spock.lang.Specification

/**
 * Tests {@link PrefService} against in-memory {@link Preference} and {@link UserPreference}
 * tables, with `alice` logged in as the default user.
 */
class PrefServiceSpec extends Specification implements ServiceUnitTest<PrefService>, DataTest, HoistUnitTest {

    Class[] getDomainClassesToMock() { [Preference, UserPreference] }

    def setup() {
        pref('theme', 'string', 'light')
        pref('pageSize', 'int', '25')
        pref('big', 'long', '6000000000')
        pref('ratio', 'double', '0.5')
        pref('compact', 'bool', 'false')
        pref('layout', 'json', '{"cols": 2}')
        pref('recent', 'json', '["a"]')
        loginAs('alice')
    }

    //-------------------
    // Reading
    //-------------------
    def 'defaults are returned, parsed by type, when the user has set nothing'() {
        expect:
        service.getString('theme') == 'light'
        service.getInt('pageSize') == 25
        service.getLong('big') == 6_000_000_000L
        service.getDouble('ratio') == 0.5d
        service.getBool('compact') == false
        service.getMap('layout') == [cols: 2]
        service.getList('recent') == ['a']
        service.isUnset('theme')
    }

    def 'set values round-trip by type for the current user'() {
        when:
        service.setString('theme', 'dark')
        service.setInt('pageSize', 50)
        service.setLong('big', 7_000_000_000L)
        service.setDouble('ratio', 0.75d)
        service.setBool('compact', true)
        service.setMap('layout', [cols: 3])
        service.setList('recent', ['b', 'c'])

        then:
        service.getString('theme') == 'dark'
        service.getInt('pageSize') == 50
        service.getLong('big') == 7_000_000_000L
        service.getDouble('ratio') == 0.75d
        service.getBool('compact')
        service.getMap('layout') == [cols: 3]
        service.getList('recent') == ['b', 'c']
        !service.isUnset('theme')
    }

    def 'values are stored per user and read for an explicit username'() {
        when:
        service.setString('theme', 'dark')
        service.setString('theme', 'solarized', 'bob')

        then:
        service.getString('theme') == 'dark'
        service.getString('theme', 'bob') == 'solarized'
        service.getString('theme', 'carol') == 'light'
        service.isUnset('theme', 'carol')
    }

    def 'the user preference records who made the change'() {
        when:
        service.setString('theme', 'dark')

        then:
        UserPreference.findByUsername('alice').lastUpdatedBy == 'alice'

        when: 'an impersonator changes the apparent user\'s preference'
        testUserService.add(new io.xh.hoist.test.fakes.TestUser('admin', ['HOIST_ADMIN']))
        impersonate('alice', 'admin')
        service.setString('theme', 'light')

        then:
        UserPreference.findByUsername('alice').lastUpdatedBy == 'admin'
    }

    def 'reading with the wrong type throws'() {
        when:
        service.getInt('theme')

        then:
        def e = thrown(RuntimeException)
        e.message == 'Unexpected type for preference: theme'
    }

    def 'an unknown preference throws on read and write'() {
        when:
        service.getString('nope')

        then:
        thrown(RuntimeException)

        when:
        service.setString('nope', 'x')

        then:
        thrown(RuntimeException)
    }

    def 'setPreference stores a raw string without a type check'() {
        when:
        service.setPreference('pageSize', '99')

        then:
        service.getInt('pageSize') == 99
    }

    def 'a value invalid for the type is not saved'() {
        when:
        service.setPreference('pageSize', 'lots')

        then:
        service.getInt('pageSize') == 25
        service.isUnset('pageSize')
    }

    //-------------------
    // Unsetting
    //-------------------
    def 'unsetPreference reverts a single preference to its default'() {
        given:
        service.setString('theme', 'dark')
        service.setInt('pageSize', 50)

        when:
        service.unsetPreference('theme')

        then:
        service.getString('theme') == 'light'
        service.getInt('pageSize') == 50
    }

    def 'unsetPreference on an unset preference is a no-op'() {
        when:
        service.unsetPreference('theme')

        then:
        noExceptionThrown()
        service.getString('theme') == 'light'
    }

    def 'clearPreferences removes all of one user\'s preferences only'() {
        given:
        service.setString('theme', 'dark')
        service.setInt('pageSize', 50)
        service.setString('theme', 'solarized', 'bob')

        when:
        service.clearPreferences()

        then:
        service.isUnset('theme')
        service.isUnset('pageSize')
        service.getString('theme', 'bob') == 'solarized'
    }

    //-------------------
    // Client config
    //-------------------
    def 'getClientConfig describes every preference for the current user'() {
        given:
        service.setString('theme', 'dark')

        when:
        def client = service.getClientConfig()

        then:
        client.keySet() == ['theme', 'pageSize', 'big', 'ratio', 'compact', 'layout', 'recent'] as Set
        client.theme == [type: 'string', value: 'dark', defaultValue: 'light', isSet: true]
        client.pageSize == [type: 'int', value: 25, defaultValue: 25, isSet: false]
        client.layout == [type: 'json', value: [cols: 2], defaultValue: [cols: 2], isSet: false]
    }

    def 'getLimitedClientConfig returns only the requested keys'() {
        when:
        def client = service.getLimitedClientConfig(['theme', 'compact', 'missing'])

        then:
        client.keySet() == ['theme', 'compact'] as Set
        client.compact.value == false
    }

    //-------------------
    // Bootstrap
    //-------------------
    def 'required preferences are created with defaults and existing ones left alone'() {
        when:
        service.ensureRequiredPrefsCreated([
            new PreferenceSpec(name: 'theme', type: 'string', defaultValue: 'ignored'),
            new PreferenceSpec(name: 'newStr', type: 'string', defaultValue: 'dflt', groupName: 'G', notes: 'n'),
            new PreferenceSpec(name: 'newJson', type: 'json', defaultValue: [x: 1]),
            [name: 'fromMap', type: 'bool', defaultValue: true],
        ])

        then:
        service.getString('theme') == 'light'
        service.getString('newStr') == 'dflt'
        service.getMap('newJson') == [x: 1]
        service.getBool('fromMap')

        and:
        with(Preference.findByName('newStr')) {
            groupName == 'G'
            notes == 'n'
            lastUpdatedBy == 'hoist-bootstrap'
        }
    }

    def 'the deprecated map form maps the legacy note key to notes'() {
        when:
        service.ensureRequiredPrefsCreated([legacy: [type: 'string', defaultValue: 'v', note: 'legacy note']])

        then:
        Preference.findByName('legacy').notes == 'legacy note'
    }

    //-------------------
    // Helpers
    //-------------------
    private Preference pref(String name, String type, String defaultValue) {
        new Preference(name: name, type: type, defaultValue: defaultValue, lastUpdatedBy: 'test')
            .save(flush: true, failOnError: true)
    }
}
