/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.test

import io.xh.hoist.config.ConfigService
import io.xh.hoist.config.TypedConfigMap

import java.lang.reflect.Method
import java.lang.reflect.Modifier

// TypedConfigMap and others log via LogSupport, which needs the Hoist test context.
class TestConfigServiceSpec extends HoistSpec {

    static class MyConfig extends TypedConfigMap {
        boolean enabled = false
        String endpoint = 'default'
        MyConfig(Map args) { init(args) }
    }

    def svc = new TestConfigService()

    def 'typed getters return values coerced to the requested type'() {
        given:
        svc.setAll(s: 'str', i: 5, l: 6, d: 1.5, b: true, p: 'secret')

        expect:
        svc.getString('s') == 'str'
        svc.getInt('i') == 5
        svc.getLong('i') == 5L
        svc.getLong('l') == 6L
        svc.getDouble('d') == 1.5d
        svc.getDouble('i') == 5d
        svc.getBool('b')
        svc.getPwd('p') == 'secret'
    }

    def 'string values are parsed for numeric and boolean getters'() {
        given:
        svc.setAll(i: '7', d: '2.5', b: 'false')

        expect:
        svc.getInt('i') == 7
        svc.getDouble('d') == 2.5d
        svc.getBool('b') == false
    }

    def 'maps and lists can be supplied as objects or JSON strings'() {
        given:
        svc.setAll(m: [a: 1], mJson: '{"a": 1}', l: [1, 2], lJson: '[1, 2]')

        expect:
        svc.getMap('m') == [a: 1]
        svc.getMap('mJson') == [a: 1]
        svc.getList('l') == [1, 2]
        svc.getList('lJson') == [1, 2]
    }

    def 'missing config throws unless a not-found value is supplied'() {
        when:
        svc.getString('missing')

        then:
        def e = thrown(RuntimeException)
        e.message == 'No config found with name: [missing]'

        expect:
        svc.getString('missing', 'dflt') == 'dflt'
        svc.getInt('missing', 3) == 3
        svc.getBool('missing', false) == false
        svc.getMap('missing', [:]) == [:]
    }

    def 'hasConfig, remove, and null values'() {
        when:
        svc.set('a', 'x')

        then:
        svc.hasConfig('a')
        !svc.hasConfig('b')

        when:
        svc.set('a', null)

        then:
        !svc.hasConfig('a')

        when:
        svc.set('a', 'y').remove('a')

        then:
        !svc.hasConfig('a')
    }

    def 'inherited derived getters work via the overridden getters'() {
        given:
        svc.setAll(users: 'alice, bob', group: '[users], carol', none: 'none')

        expect:
        svc.getStringList('users') == ['alice', 'bob']
        svc.getStringList('group') == ['alice', 'bob', 'carol']
        svc.getStringIfSet('users') == 'alice, bob'
        svc.getStringIfSet('none') == null
    }

    def 'hasGate resolves from soft config'() {
        given:
        testConfigService.setAll(gateA: 'alice', gateAll: '*')

        expect:
        new TestUser('alice').hasGate('gateA')
        !new TestUser('bob').hasGate('gateA')
        new TestUser('bob').hasGate('gateAll')
    }

    def 'typed configs load via getObject once registered'() {
        given:
        svc.registerTypedConfig('myConfig', MyConfig).set('myConfig', [enabled: true])

        when:
        def obj = svc.getObject(MyConfig)

        then:
        obj.enabled
        obj.endpoint == 'default'
        svc.getTypedClass('myConfig') == MyConfig
    }

    def 'getObject of an unregistered class throws'() {
        when:
        svc.getObject(MyConfig)

        then:
        thrown(RuntimeException)
    }

    def 'admin stats, client config, and change events are inert'() {
        given:
        svc.set('a', 1)

        expect:
        svc.getForAdminStats('a', 'b') == [a: 1, b: null]
        svc.getClientConfig() == [:]

        when:
        svc.fireConfigChanged(null)

        then:
        noExceptionThrown()
    }

    def 'clear removes all values'() {
        when:
        svc.set('a', 1).clear()

        then:
        !svc.hasConfig('a')
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
