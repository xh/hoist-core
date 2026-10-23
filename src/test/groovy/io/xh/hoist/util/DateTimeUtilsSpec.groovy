/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.util

import io.xh.hoist.test.HoistSpec
import spock.lang.Unroll

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException

import static io.xh.hoist.util.DateTimeUtils.*

class DateTimeUtilsSpec extends HoistSpec {

    def 'constants have expected values'() {
        expect:
        ONE_DAY == 86_400_000L
        ONE_HOUR == 3_600_000L
        ONE_MINUTE == 60_000L
        ONE_SECOND == 1000L
        DATE_FMT == 'yyyy-MM-dd'
        DATETIME_FMT == 'yyyy-MM-dd h:mma'
    }

    @Unroll
    def 'asEpochMilli(#input) -> #expected'() {
        expect:
        asEpochMilli(input) == expected

        where:
        input                     | expected
        null                      | null
        5L                        | 5L
        new Date(1000)            | 1000L
        Instant.ofEpochMilli(7)   | 7L
    }

    def 'asEpochMilli rejects unsupported types'() {
        when:
        asEpochMilli('x')

        then:
        thrown(IllegalArgumentException)
    }

    def 'intervalElapsed is true for a null start'() {
        expect:
        intervalElapsed(ONE_MINUTE, null)
    }

    def 'intervalElapsed compares against the current time'() {
        expect:
        !intervalElapsed(ONE_MINUTE, System.currentTimeMillis())
        !intervalElapsed(ONE_MINUTE, new Date())
        intervalElapsed(ONE_MINUTE, System.currentTimeMillis() - 2 * ONE_MINUTE)
        intervalElapsed(ONE_MINUTE, Instant.now().minusSeconds(120))
    }

    @Unroll
    def 'parseLocalDate(#input) -> #expected'() {
        expect:
        parseLocalDate(input) == expected

        where:
        input        | expected
        '20240105'   | LocalDate.of(2024, 1, 5)
        '2024-01-05' | LocalDate.of(2024, 1, 5)
        null         | null
        ''           | null
    }

    @Unroll
    def 'parseLocalDate rejects #input'() {
        when:
        parseLocalDate(input)

        then:
        thrown(DateTimeParseException)

        where:
        input << ['2024-1-5', '2024-02-30', '2024-13-01']
    }

    //------------------------------------------
    // Zone-aware helpers, with test environmentService
    //------------------------------------------
    def 'app day is calculated in the app time zone'() {
        given:
        useAppTimeZone('America/New_York')
        def date = Date.from(Instant.parse('2024-01-05T03:00:00Z'))

        expect:
        appTimeZone.toZoneId() == ZoneId.of('America/New_York')
        appDay(date) == LocalDate.of(2024, 1, 4)
    }

    def 'server day is calculated in the server time zone'() {
        given:
        useAppTimeZone('America/New_York')
        def date = Date.from(Instant.parse('2024-01-05T03:00:00Z'))

        expect:
        serverDay(date) == date.toInstant().atZone(serverZoneId).toLocalDate()
    }

    def 'appStartOfDay and appEndOfDay bound the day in the app time zone'() {
        given:
        useAppTimeZone('America/New_York')
        def day = LocalDate.of(2024, 1, 5)

        expect:
        appStartOfDay(day).toInstant() == Instant.parse('2024-01-05T05:00:00Z')
        appEndOfDay(day).toInstant() == Instant.parse('2024-01-06T04:59:59.999Z')
    }

    def 'appStartOfDay handles the daylight saving start day'() {
        given:
        useAppTimeZone('America/New_York')

        expect:
        // 2024-03-10 begins at UTC-5 and ends at UTC-4, so the day is only 23 hours long
        appEndOfDay(LocalDate.of(2024, 3, 10)).time - appStartOfDay(LocalDate.of(2024, 3, 10)).time ==
            23 * ONE_HOUR - 1
    }
}
