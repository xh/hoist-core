/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.test

import io.xh.hoist.config.ConfigService
import io.xh.hoist.config.TypedConfigMap
import io.xh.hoist.test.fakes.*

import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Covers the points where {@link TestConfigService} must agree with `ConfigService`: value
 * coercion, the missing-config contract, typed configs, and the set of overridden methods.
 */
class TestConfigServiceSpec extends HoistSpec {

    static class MyConfig extends TypedConfigMap {
        boolean enabled = false
        String endpoint = 'default'
        MyConfig(Map args) { init(args) }
    }

    def svc = new TestConfigService()

    def 'values are coerced to the type of the getter, from objects or strings'() {
        given:
        svc.setAll(i: 5, iStr: '7', d: 1.5, dStr: '2.5', b: true, bStr: 'false', m: [a: 1], mJson: '{"a": 1}', l: [1, 2], lJson: '[1, 2]')

        expect:
        svc.getInt('i') == 5
        svc.getInt('iStr') == 7
        svc.getLong('i') == 5L
        svc.getDouble('i') == 5d
        svc.getDouble('d') == 1.5d
        svc.getDouble('dStr') == 2.5d
        svc.getBool('b')
        svc.getBool('bStr') == false
        svc.getMap('m') == [a: 1]
        svc.getMap('mJson') == [a: 1]
        svc.getList('l') == [1, 2]
        svc.getList('lJson') == [1, 2]
        svc.getString('i') == '5'
    }

    def 'missing config throws unless a not-found value is supplied, as in ConfigService'() {
        when:
        svc.getString('missing')

        then:
        def e = thrown(RuntimeException)
        e.message == 'No config found with name: [missing]'

        expect:
        svc.getString('missing', 'dflt') == 'dflt'
        svc.getInt('missing', 3) == 3
        !svc.hasConfig('missing')
    }

    def 'typed configs load via getObject once registered, and throw otherwise'() {
        when:
        svc.getObject(MyConfig)

        then:
        thrown(RuntimeException)

        when:
        svc.registerTypedConfig('myConfig', MyConfig).set('myConfig', [enabled: true])
        def obj = svc.getObject(MyConfig)

        then:
        obj.enabled
        obj.endpoint == 'default'
        svc.getTypedClass('myConfig') == MyConfig
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
