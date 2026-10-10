/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.track

import io.xh.hoist.json.JSONParser
import io.xh.hoist.json.JSONSerializer
import io.xh.hoist.test.HoistSpec

import static io.xh.hoist.track.ActivityTrackingConfig.MaxRows
import static io.xh.hoist.track.ActivityTrackingConfig.TrackLogLevel

class ActivityTrackingConfigSpec extends HoistSpec {

    def 'defaults are stable'() {
        when:
        def cfg = new ActivityTrackingConfig([:])

        then:
        cfg.enabled
        cfg.logData == false
        cfg.maxDataLength == 2000
        cfg.maxElapsedMins == 2
        cfg.maxEntriesPerMin == 1000L
        cfg.levels.size() == 1
        cfg.levels[0].username == '*'
        cfg.clientHealthReport.intervalMins == -1
        cfg.maxRows.defaultValue == 10000
        cfg.maxRows.limit == 25000
        cfg.maxRows.options == [1000, 5000, 10000, 25000]
    }

    def 'logData accepts boolean or list values'() {
        expect:
        new ActivityTrackingConfig([logData: value]).logData == value

        where:
        value << [true, false, ['a', 'b.c']]
    }

    def 'partial maxRows merges with its defaults'() {
        when:
        def cfg = new ActivityTrackingConfig([maxRows: [limit: 100]])

        then:
        cfg.maxRows.limit == 100
        cfg.maxRows.defaultValue == 10000
        cfg.maxRows.options == [1000, 5000, 10000, 25000]
    }

    def 'levels are converted to TrackLogLevel with defaults filled in'() {
        when:
        def cfg = new ActivityTrackingConfig([levels: [[username: 'bob', severity: 'DEBUG'], [category: 'x']]])

        then:
        cfg.levels.every { it instanceof TrackLogLevel }
        cfg.levels[0].username == 'bob'
        cfg.levels[0].category == '*'
        cfg.levels[0].severity == 'DEBUG'
        cfg.levels[1].category == 'x'
        cfg.levels[1].username == '*'
    }

    def 'nested non-Map value is rejected by the type check'() {
        when:
        new ActivityTrackingConfig([clientHealthReport: 5])

        then:
        thrown(IllegalArgumentException)
    }

    def 'MaxRows translates default key and does not mutate caller input'() {
        given:
        def input = [default: 50]

        when:
        def rows = new MaxRows(input)

        then:
        rows.defaultValue == 50
        input == [default: 50]
        rows.formatForJSON().containsKey('default')
        !rows.formatForJSON().containsKey('defaultValue')
        rows.formatForJSON().default == 50
    }

    def 'config round trips through JSON'() {
        given:
        def cfg = new ActivityTrackingConfig([maxRows: [default: 50, limit: 99], levels: [[username: 'bob']]])

        when:
        def copy = new ActivityTrackingConfig(JSONParser.parseObject(JSONSerializer.serialize(cfg)))

        then:
        copy.maxRows.defaultValue == 50
        copy.maxRows.limit == 99
        copy.levels*.username == ['bob']
        JSONSerializer.serialize(copy) == JSONSerializer.serialize(cfg)
    }
}
