/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.config

import io.xh.hoist.alertbanner.AlertBannerConfig
import io.xh.hoist.entra.EntraIdConfig
import io.xh.hoist.environment.EnvPollConfig
import io.xh.hoist.export.ExportConfig
import io.xh.hoist.json.JSONParser
import io.xh.hoist.json.JSONSerializer
import io.xh.hoist.jsonblob.JsonBlobConfig
import io.xh.hoist.ldap.LdapConfig
import io.xh.hoist.monitor.MonitorConfig
import io.xh.hoist.test.HoistSpec
import io.xh.hoist.track.ActivityTrackingConfig
import io.xh.hoist.track.ClientErrorConfig
import io.xh.hoist.websocket.WebSocketConfig
import spock.lang.Unroll

class TypedConfigMapSpec extends HoistSpec {

    static class Inner extends TypedConfigMap {
        Integer x = 0
        Inner(Map args) { init(args) }
    }

    static class ParentConfig extends TypedConfigMap {
        String name = 'parent'
        Integer parentOnly = 1
        ParentConfig(Map args) { init(args) }
    }

    static class SubConfig extends ParentConfig {
        String name = 'child'
        Integer childOnly = 2
        SubConfig(Map args) {
            super([:])
            init(args)
        }
    }

    static class NestingConfig extends TypedConfigMap {
        boolean flag = false
        Integer count
        Inner inner = new Inner([x: 7])
        Inner unsetInner
        List<Inner> innerList = []
        Map<String, Inner> inners = [:]
        List<String> strings = []
        Map<String, List<String>> roles = ['*': ['A']]
        static Integer staticField = 99
        NestingConfig(Map args) { init(args) }
    }

    def 'null and empty args keep the declared defaults'() {
        expect:
        new IdleConfig(args).timeout == 120
        new IdleConfig(args).appTimeouts == [:]

        where:
        args << [null, [:]]
    }

    def 'supplied values override defaults'() {
        when:
        def cfg = new IdleConfig([timeout: 30, appTimeouts: [a: 5]])

        then:
        cfg.timeout == 30
        cfg.appTimeouts == [a: 5]
    }

    def 'unknown keys are ignored rather than thrown'() {
        expect:
        new IdleConfig([bogusKeyForTypedMapSpec: 1]).formatForJSON() == [timeout: 120, appTimeouts: [:]]
    }

    @Unroll
    def 'wrong typed value #value for #key is rejected'() {
        when:
        new NestingConfig([(key): value])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message == "Field '$key' on NestingConfig expects $expected but got $actual"

        where:
        key       | value  || expected  | actual
        'flag'    | 5      || 'Boolean' | 'Integer'
        'flag'    | 'true' || 'Boolean' | 'String'
        'count'   | '120'  || 'Integer' | 'String'
        'inner'   | 5      || 'Inner'   | 'Integer'
        'strings' | 'host' || 'List'    | 'String'
        'roles'   | [1, 2] || 'Map'     | 'ArrayList'
    }

    def 'Number values are accepted for numeric fields and null is allowed'() {
        expect:
        new IdleConfig([timeout: 5L]).timeout == 5
        new IdleConfig([timeout: 5L]).timeout instanceof Integer
        new IdleConfig([timeout: 5.0d]).timeout == 5
        new IdleConfig([timeout: null]).timeout == null
        new NestingConfig([count: null]).count == null
    }

    def 'fractional value is rejected for an Integer field'() {
        when:
        new IdleConfig([timeout: 2.7d])

        then:
        thrown(IllegalArgumentException)
    }

    def 'out of range Long is rejected for an Integer field'() {
        when:
        new IdleConfig([timeout: 3_000_000_000L])

        then:
        thrown(IllegalArgumentException)
    }

    def 'nested config given a Map is merged into the existing default instance'() {
        when:
        def cfg = new NestingConfig([inner: [x: 9]])

        then:
        cfg.inner.x == 9
    }

    def 'nested config with no default is constructed from a Map'() {
        when:
        def cfg = new NestingConfig([unsetInner: [x: 3]])

        then:
        cfg.unsetInner instanceof Inner
        cfg.unsetInner.x == 3
    }

    def 'List of nested configs converts Maps and passes through other items'() {
        when:
        def cfg = new NestingConfig([innerList: [[x: 1], 'raw']])

        then:
        cfg.innerList[0] instanceof Inner
        cfg.innerList[0].x == 1
        cfg.innerList[1] == 'raw'
    }

    def 'Map of nested configs converts Map values and passes through other values'() {
        when:
        def cfg = new NestingConfig([inners: [a: [x: 1], b: 'raw']])

        then:
        cfg.inners.a instanceof Inner
        cfg.inners.a.x == 1
        cfg.inners.b == 'raw'
    }

    def 'List of Strings is assigned as supplied'() {
        expect:
        new NestingConfig([strings: ['a', 'b']]).strings == ['a', 'b']
    }

    def 'Map with a parameterized value type replaces the default wholesale'() {
        expect:
        new NestingConfig([roles: [foo: ['B']]]).roles == [foo: ['B']]
    }

    def 'formatForJSON emits exactly the declared instance fields'() {
        expect:
        new NestingConfig([:]).formatForJSON().keySet() ==
            ['flag', 'count', 'inner', 'unsetInner', 'innerList', 'inners', 'strings', 'roles'] as Set
    }

    def 'subclass inherits parent fields and its own same-named field wins'() {
        when:
        def cfg = new SubConfig([parentOnly: 5])
        def json = cfg.formatForJSON()

        then:
        json.keySet() == ['name', 'childOnly', 'parentOnly'] as Set
        json.parentOnly == 5
        json.childOnly == 2
        cfg.name == 'child'
    }

    def 'serialized config round trips through JSON'() {
        given:
        def cfg = new NestingConfig([flag: true, count: 4, inner: [x: 2], innerList: [[x: 8]]])

        when:
        def copy = new NestingConfig(JSONParser.parseObject(JSONSerializer.serialize(cfg)))

        then:
        JSONSerializer.serialize(copy) == JSONSerializer.serialize(cfg)
        copy.innerList[0].x == 8
    }

    def 'all hoist TypedConfigMap subclasses expose a Map constructor'() {
        expect:
        clazz.getDeclaredConstructor(Map) != null

        where:
        clazz << [
            IdleConfig, EnvPollConfig, ActivityTrackingConfig, LdapConfig, EntraIdConfig,
            MonitorConfig, JsonBlobConfig, AlertBannerConfig, ExportConfig, WebSocketConfig,
            ClientErrorConfig, ChangelogConfig
        ]
    }
}
