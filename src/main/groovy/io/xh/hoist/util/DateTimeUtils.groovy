/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.util

import groovy.transform.CompileStatic

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

import static java.lang.System.currentTimeMillis
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

    /**
     * @param intervalInMs
     * @param startTimestamp to compare against, as Date, Instant, or Long.
     * @returns true if at least intervalInMs has elapsed since startTimestamp.
     */
    static boolean intervalElapsed(Long intervalInMs, Object startTimestamp) {
        if (startTimestamp == null) return true
        Long startMs = asEpochMilli(startTimestamp)
        return currentTimeMillis() > (startMs + intervalInMs)
    }

    /**
     * @param timestamp to convert, as Date, Instant, or Long (to be returned as-is)
     * @returns timestamp as epoch milliseconds, or null if timestamp is null.
     */
    static Long asEpochMilli(Object timestamp) {
        if (timestamp == null) return null
        if (timestamp instanceof Date) return ((Date) timestamp).time
        if (timestamp instanceof Instant) return ((Instant) timestamp).toEpochMilli()
        if (timestamp instanceof Long) return (Long) timestamp
        throw new IllegalArgumentException("Invalid timestamp: ${timestamp}")
    }

    /**
     * @param dateString in 'YYYYMMDD' or 'YYYY-MM-DD' format.
     * @returns parsed LocalDate, or null if dateString is null.
     */
    static LocalDate parseLocalDate(String dateString) {
        if (!dateString) return null
        return LocalDate.parse(dateString.replace('-', ''), BASIC_ISO_DATE)
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

}
