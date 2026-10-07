/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.test

import io.xh.hoist.config.ConfigService
import io.xh.hoist.config.TypedConfigMap
import spock.lang.Specification

import java.lang.reflect.Method
import java.lang.reflect.Modifier

@HoistTest   // TypedConfigMap and others log via LogSupport, which needs a test context
class TestConfigServiceSpec extends Specification {

    static class MyConfig extends TypedConfigMap {
        boolean enabled = false
        String endpoint = 'default'
        MyConfig(Map args) { init(args) }
    }

    def config = new TestConfigService()

    def 'typed getters return values coerced to the requested type'() {
        given:
        config.setAll(s: 'str', i: 5, l: 6, d: 1.5, b: true, p: 'secret')

        expect:
        config.getString('s') == 'str'
        config.getInt('i') == 5
        config.getLong('i') == 5L
        config.getLong('l') == 6L
        config.getDouble('d') == 1.5d
        config.getDouble('i') == 5d
        config.getBool('b')
        config.getPwd('p') == 'secret'
    }

    def 'string values are parsed for numeric and boolean getters'() {
        given:
        config.setAll(i: '7', d: '2.5', b: 'false')

        expect:
        config.getInt('i') == 7
        config.getDouble('d') == 2.5d
        config.getBool('b') == false
    }

    def 'maps and lists can be supplied as objects or JSON strings'() {
        given:
        config.setAll(m: [a: 1], mJson: '{"a": 1}', l: [1, 2], lJson: '[1, 2]')

        expect:
        config.getMap('m') == [a: 1]
        config.getMap('mJson') == [a: 1]
        config.getList('l') == [1, 2]
        config.getList('lJson') == [1, 2]
    }

    def 'missing config throws unless a not-found value is supplied'() {
        when:
        config.getString('missing')

        then:
        def e = thrown(RuntimeException)
        e.message == 'No config found with name: [missing]'

        expect:
        config.getString('missing', 'dflt') == 'dflt'
        config.getInt('missing', 3) == 3
        config.getBool('missing', false) == false
        config.getMap('missing', [:]) == [:]
    }

    def 'hasConfig, remove, and null values'() {
        when:
        config.set('a', 'x')

        then:
        config.hasConfig('a')
        !config.hasConfig('b')

        when:
        config.set('a', null)

        then:
        !config.hasConfig('a')

        when:
        config.set('a', 'y').remove('a')

        then:
        !config.hasConfig('a')
    }

    def 'inherited derived getters work via the overridden getters'() {
        given:
        config.setAll(users: 'alice, bob', group: '[users], carol', none: 'none')

        expect:
        config.getStringList('users') == ['alice', 'bob']
        config.getStringList('group') == ['alice', 'bob', 'carol']
        config.getStringIfSet('users') == 'alice, bob'
        config.getStringIfSet('none') == null
    }

    def 'hasGate resolves from soft config'() {
        given:
        HoistTestContext.current.configService.setAll(gateA: 'alice', gateAll: '*')

        expect:
        new TestUser('alice').hasGate('gateA')
        !new TestUser('bob').hasGate('gateA')
        new TestUser('bob').hasGate('gateAll')
    }

    def 'typed configs load via getObject once registered'() {
        given:
        config.registerTypedConfig('myConfig', MyConfig).set('myConfig', [enabled: true])

        when:
        def obj = config.getObject(MyConfig)

        then:
        obj.enabled
        obj.endpoint == 'default'
        config.getTypedClass('myConfig') == MyConfig
    }

    def 'getObject of an unregistered class throws'() {
        when:
        config.getObject(MyConfig)

        then:
        thrown(RuntimeException)
    }

    def 'admin stats, client config, and change events are inert'() {
        given:
        config.set('a', 1)

        expect:
        config.getForAdminStats('a', 'b') == [a: 1, b: null]
        config.getClientConfig() == [:]

        when:
        config.fireConfigChanged(null)

        then:
        noExceptionThrown()
    }

    def 'clear removes all values'() {
        when:
        config.set('a', 1).clear()

        then:
        !config.hasConfig('a')
    }

    //------------------------------------------------------------------------------------------
    // Drift guard - fails when ConfigService gains a public getter that TestConfigService has
    // not been updated to override, which would silently fall through to the database-backed one.
    //------------------------------------------------------------------------------------------
    // Derived getters implemented in terms of the overridden ones, so intentionally inherited.
    static final Set<String> DERIVED = ['getStringIfSet', 'getPwdIfSet', 'getStringList'] as Set

    // Accessors added to the class by the Groovy/Grails compiler transforms, not part of its API.
    static final Set<String> FRAMEWORK_INJECTED = ['getApplicationContext', 'getMetaClass', 'getTransactionManager', 'getTargetDatastore'] as Set

    def 'TestConfigService overrides every public get/has method of ConfigService'() {
        given:
        def candidates = ConfigService.declaredMethods.findAll { Method m ->
            Modifier.isPublic(m.modifiers) && !m.synthetic && !m.bridge &&
                m.name ==~ /(get|has)[A-Z].*/ &&
                !DERIVED.contains(m.name) &&
                !FRAMEWORK_INJECTED.contains(m.name)
        }

        expect: 'the check is not vacuous'
        candidates.size() >= 10

        when:
        def notOverridden = candidates.findAll { Method m ->
            !TestConfigService.declaredMethods.any { it.name == m.name && it.parameterTypes == m.parameterTypes }
        }

        then:
        notOverridden.isEmpty()
    }
}
