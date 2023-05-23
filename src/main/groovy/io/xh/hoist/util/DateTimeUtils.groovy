/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.util

import groovy.transform.CompileStatic

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

import static java.time.format.DateTimeFormatter.BASIC_ISO_DATE

@CompileStatic
class DateTimeUtils {
    static final Long ONE_SECOND = 1000
    static final Long SECONDS = 1000
    static final Long ONE_MINUTE = 60 * 1000
    static final Long MINUTES = 60 * 1000
    static final Long ONE_HOUR = 60 * 60 * 1000
    static final Long HOURS = 60 * 60 * 1000
    static final Long ONE_DAY = 24 * 60 * 60 * 1000
    static final Long DAYS = 24 * 60 * 60 * 1000

    static final String YEAR_OF_WEEK = 'YYYY'
    static final String CALENDAR_YEAR = 'yyyy'

    static final String DATE_FMT = "${CALENDAR_YEAR}-MM-dd"
    static final String DATETIME_FMT = "${CALENDAR_YEAR}-MM-dd h:mma"
    static final String TIME_FMT = 'h:mma'
    static final String MONTH_DAY_FMT = 'MMM d'

    static boolean intervalElapsed(Long interval, Object lastRun) {
        if (!lastRun) return true
        Long lastRunMs = lastRun instanceof Date ? ((Date) lastRun).time : (Long) lastRun
        return System.currentTimeMillis() > lastRunMs + interval
    }

    static TimeZone getAppTimeZone() {
        return Utils.environmentService.appTimeZone
    }

    static ZoneId getAppZoneId() {
        return appTimeZone.toZoneId()
    }

    static TimeZone getServerTimeZone() {
        return Utils.environmentService.serverTimeZone
    }

    static ZoneId getServerZoneId() {
        return serverTimeZone.toZoneId()
    }

    /**
     * Validate that the JVM is running in an expected/required TimeZone - will throw an exception
     * if the server is not running in the requested zone (or if the requested zone is invalid).
     * Call from an application's `Bootstrap.groovy` when a particular timezone is required (e.g.
     * to match the timezone of the app's database server).
     */
    static void ensureServerTimeZoneIs(String zoneId) {
        def reqZone = ZoneId.of(zoneId)
        if (serverZoneId != reqZone) {
            throw new IllegalStateException("Server TimeZone is ${serverZoneId}, not ${reqZone} - please set JVM arg '-Duser.timezone=${reqZone}'")
        }
    }

    static LocalDate appDay(Date forDate = null) {
        forDate ? forDate.toInstant().atZone(appZoneId).toLocalDate() : LocalDate.now(appZoneId)
    }

    static LocalDate serverDay(Date forDate = null) {
        forDate ? forDate.toInstant().atZone(serverZoneId).toLocalDate() : LocalDate.now(serverZoneId)
    }

    static Date appStartOfDay(LocalDate localDate = LocalDate.now(appZoneId)) {
        Date.from(localDate.atStartOfDay().atZone(appZoneId).toInstant())
    }

    static Date serverStartOfDay(LocalDate localDate = LocalDate.now(serverZoneId)) {
        Date.from(localDate.atStartOfDay().atZone(serverZoneId).toInstant())
    }

    static Date appEndOfDay(LocalDate localDate = LocalDate.now(appZoneId)) {
        Date.from(localDate.atTime(LocalTime.MAX).atZone(appZoneId).toInstant())
    }

    static Date serverEndOfDay(LocalDate localDate = LocalDate.now(serverZoneId)) {
        Date.from(localDate.atTime(LocalTime.MAX).atZone(serverZoneId).toInstant())
    }

    /**
     * Parse a local date
     * @param s - Valid date in 'YYYYMMDD' or 'YYYY-MM-DD' format.
     */
    static LocalDate parseLocalDate(String s) {
        if (!s) return null
        return LocalDate.parse(s.replace('-', ''), BASIC_ISO_DATE)
    }
}
