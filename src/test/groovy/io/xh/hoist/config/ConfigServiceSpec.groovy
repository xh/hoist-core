/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.config

import grails.testing.gorm.DataTest
import grails.testing.services.ServiceUnitTest
import io.xh.hoist.test.HoistUnitTest
import io.xh.hoist.test.fakes.TestUser
import spock.lang.Specification

/**
 * Tests {@link ConfigService} against an in-memory {@link AppConfig} table,
 * via the standard Grails `ServiceUnitTest` + `DataTest` traits combined with `HoistUnitTest`.
 */
class ConfigServiceSpec extends Specification implements ServiceUnitTest<ConfigService>, DataTest, HoistUnitTest {

    static class IdleConfig extends TypedConfigMap {
        Integer timeout = 30
        boolean enabled = true
        IdleConfig(Map args) { init(args) }
    }

    Class[] getDomainClassesToMock() { [AppConfig] }

    //-------------------
    // Typed getters
    //-------------------
    def 'typed getters parse the stored string by value type'() {
        given:
        config('s', 'string', 'hello')
        config('i', 'int', '5')
        config('l', 'long', '6000000000')
        config('d', 'double', '1.5')
        config('b', 'bool', 'true')
        config('m', 'json', '{"a": 1}')
        config('a', 'json', '[1, 2]')

        expect:
        service.getString('s') == 'hello'
        service.getInt('i') == 5
        service.getLong('l') == 6_000_000_000L
        service.getDouble('d') == 1.5d
        service.getBool('b')
        service.getMap('m') == [a: 1]
        service.getList('a') == [1, 2]
    }

    def 'reading a config with the wrong getter throws'() {
        given:
        config('i', 'int', '5')

        when:
        service.getString('i')

        then:
        def e = thrown(RuntimeException)
        e.message.contains('Unexpected type')
    }

    def 'missing config throws unless a not-found value is given'() {
        when:
        service.getString('missing')

        then:
        def e = thrown(RuntimeException)
        e.message == 'No config found with name: [missing]'

        expect:
        service.getString('missing', 'dflt') == 'dflt'
        service.getInt('missing', 3) == 3
        service.hasConfig('missing') == false
    }

    def 'getStringIfSet and getPwdIfSet map the NONE placeholder to null'() {
        given:
        config('unsetStr', 'string', AppConfig.NONE)
        config('setStr', 'string', 'x')
        config('unsetPwd', 'pwd', AppConfig.NONE)

        expect:
        service.getStringIfSet('unsetStr') == null
        service.getStringIfSet('setStr') == 'x'
        service.getPwdIfSet('unsetPwd') == null

        when: 'an absent config throws'
        service.getStringIfSet('absent')

        then:
        thrown(RuntimeException)
    }

    def 'pwd configs are encrypted at rest and decrypted on read'() {
        given:
        def saved = config('secret', 'pwd', 'hunter2')

        expect:
        saved.value != 'hunter2'
        service.getPwd('secret') == 'hunter2'
        service.getForAdminStats('secret') == [secret: '*********']
    }

    def 'getStringList splits, trims and expands nested [group] references'() {
        given:
        config('admins', 'string', 'alice, bob')
        config('all', 'string', '[admins], carol ,dave')

        expect:
        service.getStringList('admins') == ['alice', 'bob']
        service.getStringList('all') == ['alice', 'bob', 'carol', 'dave']
    }

    //-------------------
    // setValue
    //-------------------
    def 'setValue updates the stored value and records the current user'() {
        given:
        config('greeting', 'string', 'hi')
        loginAs(new TestUser('alice'))

        when:
        service.setValue('greeting', 'hello')

        then:
        service.getString('greeting') == 'hello'
        AppConfig.findByName('greeting').lastUpdatedBy == 'alice'
    }

    def 'setValue falls back to a service username when no user is logged in'() {
        given:
        config('greeting', 'string', 'hi')

        when:
        service.setValue('greeting', 'hello')

        then:
        AppConfig.findByName('greeting').lastUpdatedBy == 'hoist-config-service'
    }

    def 'setValue serializes a non-string value for a json config'() {
        given:
        config('opts', 'json', '{}')

        when:
        service.setValue('opts', [a: 1, b: [2, 3]])

        then:
        service.getMap('opts') == [a: 1, b: [2, 3]]
        AppConfig.findByName('opts').value.contains('\n')   // pretty-printed
    }

    def 'setValue on a missing config throws'() {
        when:
        service.setValue('missing', 'x')

        then:
        def e = thrown(RuntimeException)
        e.message == 'No config found with name: [missing]'
    }

    def 'setValue with an invalid value for the type fails validation'() {
        given:
        config('i', 'int', '5')

        when:
        def ret = service.setValue('i', 'not a number')

        then:
        ret == null
        AppConfig.findByName('i').hasErrors()
    }

    //-------------------
    // ensureRequiredConfigsCreated
    //-------------------
    def 'required configs are created with defaults and existing ones left alone'() {
        given:
        config('existing', 'string', 'keep me')

        when:
        service.ensureRequiredConfigsCreated([
            new ConfigSpec(name: 'existing', valueType: 'string', defaultValue: 'ignored'),
            new ConfigSpec(name: 'newStr', valueType: 'string', defaultValue: 'dflt', clientVisible: true, groupName: 'G', note: 'n'),
            new ConfigSpec(name: 'newJson', valueType: 'json', defaultValue: [x: 1]),
            [name: 'fromMap', valueType: 'int', defaultValue: 7],
        ])

        then:
        service.getString('existing') == 'keep me'
        service.getString('newStr') == 'dflt'
        service.getMap('newJson') == [x: 1]
        service.getInt('fromMap') == 7

        and:
        with(AppConfig.findByName('newStr')) {
            clientVisible
            groupName == 'G'
            note == 'n'
            lastUpdatedBy == 'hoist-bootstrap'
        }
    }

    def 'a typed config is seeded empty, registered, and readable via getObject with defaults applied'() {
        when:
        service.ensureRequiredConfigsCreated([new ConfigSpec(name: 'idle', valueType: 'json', typedClass: IdleConfig)])

        then:
        service.getMap('idle') == [:]
        service.getTypedClass('idle') == IdleConfig

        when:
        def obj = service.getObject(IdleConfig)

        then:
        obj.timeout == 30
        obj.enabled

        when:
        service.setValue('idle', [timeout: 5])

        then:
        with(service.getObject(IdleConfig)) {
            timeout == 5
            enabled
        }
    }

    def 'getObject for an unregistered class throws'() {
        when:
        service.getObject(IdleConfig)

        then:
        def e = thrown(RuntimeException)
        e.message.contains('not registered as a typedClass')
    }

    def 'AppConfig validation rejects a value the typed class cannot accept'() {
        given:
        service.ensureRequiredConfigsCreated([new ConfigSpec(name: 'idle', valueType: 'json', typedClass: IdleConfig)])

        when:
        def ret = service.setValue('idle', [timeout: 'soon'])

        then:
        ret == null
        AppConfig.findByName('idle').errors.getFieldError('value').codes.any {
            it.contains('default.invalid.typedConfig.message')
        }
    }

    //-------------------
    // Client and admin views
    //-------------------
    def 'getClientConfig returns only clientVisible configs, with passwords obscured and json as objects'() {
        given:
        config('visible', 'string', 'v', clientVisible: true)
        config('hidden', 'string', 'h')
        config('pwd', 'pwd', 'secret', clientVisible: true)
        config('json', 'json', '{"a": 1}', clientVisible: true)

        expect:
        service.getClientConfig() == [visible: 'v', pwd: '*********', json: [a: 1]]
    }

    def 'getClientConfig instantiates registered typed classes'() {
        given:
        service.ensureRequiredConfigsCreated([
            new ConfigSpec(name: 'idle', valueType: 'json', typedClass: IdleConfig, clientVisible: true)
        ])

        when:
        def client = service.getClientConfig()

        then:
        client.idle instanceof IdleConfig
        client.idle.timeout == 30
    }

    def 'resolved and default values are available for typed configs only'() {
        given:
        service.ensureRequiredConfigsCreated([new ConfigSpec(name: 'idle', valueType: 'json', typedClass: IdleConfig)])
        service.setValue('idle', [timeout: 5])
        config('plain', 'json', '{"a": 1}')

        expect:
        service.getResolvedConfigValue(AppConfig.findByName('idle')) == [timeout: 5, enabled: true]
        service.getDefaultConfigValue(AppConfig.findByName('idle')) == [timeout: 30, enabled: true]
        service.getResolvedConfigValue(AppConfig.findByName('plain')) == null
        service.getDefaultConfigValue(AppConfig.findByName('plain')) == null
    }

    //-------------------
    // Helpers
    //-------------------
    private AppConfig config(Map extra = [:], String name, String valueType, String value) {
        new AppConfig([name: name, valueType: valueType, value: value, lastUpdatedBy: 'test'] + extra)
            .save(flush: true, failOnError: true)
    }
}
