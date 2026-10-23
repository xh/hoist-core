/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.track

import spock.lang.Specification

class TrackSeveritySpec extends Specification {

    def 'parse of #input gives #expected'() {
        expect:
        TrackSeverity.parse(input) == expected

        where:
        input     | expected
        null      | TrackSeverity.INFO
        ''        | TrackSeverity.INFO
        '   '     | TrackSeverity.INFO
        'debug'   | TrackSeverity.DEBUG
        'Warn'    | TrackSeverity.WARN
        ' error ' | TrackSeverity.ERROR
        'ERROR'   | TrackSeverity.ERROR
        'fatal'   | TrackSeverity.INFO
        'INFO '   | TrackSeverity.INFO
    }

    def 'severities are ordered from least to most severe'() {
        expect:
        TrackSeverity.values().toList() ==
            [TrackSeverity.DEBUG, TrackSeverity.INFO, TrackSeverity.WARN, TrackSeverity.ERROR]
        TrackSeverity.DEBUG < TrackSeverity.INFO
        TrackSeverity.WARN < TrackSeverity.ERROR
    }
}
