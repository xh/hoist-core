/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.environment

import io.xh.hoist.json.JSONSerializer
import io.xh.hoist.test.HoistSpec
import spock.lang.Unroll

class EnvPollConfigSpec extends HoistSpec {

    def 'defaults apply when no args are supplied'() {
        expect:
        new EnvPollConfig(args).interval == 10
        new EnvPollConfig(args).onVersionChange == 'promptReload'

        where:
        args << [null, [:]]
    }

    def 'supplied values override defaults'() {
        when:
        def cfg = new EnvPollConfig([interval: -1, onVersionChange: 'forceReload'])

        then:
        cfg.interval == -1
        cfg.onVersionChange == 'forceReload'
    }

    @Unroll
    def 'rejects #key of wrong type #value'() {
        when:
        new EnvPollConfig([(key): value])

        then:
        thrown(IllegalArgumentException)

        where:
        key               | value
        'interval'        | '5'
        'onVersionChange' | 5
    }

    def 'null interval is allowed and long is converted'() {
        expect:
        new EnvPollConfig([interval: null]).interval == null
        new EnvPollConfig([interval: 5L]).interval == 5
    }

    def 'serializes to exactly the declared shape'() {
        given:
        def cfg = new EnvPollConfig([:])

        expect:
        cfg.formatForJSON() == [interval: 10, onVersionChange: 'promptReload']
        JSONSerializer.serialize(cfg) == JSONSerializer.serialize([interval: 10, onVersionChange: 'promptReload'])
    }
}
