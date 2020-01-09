/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2020 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.util

import groovy.transform.CompileStatic

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
    
    static final String DATE_FMT = "${CALENDAR_YEAR}-MM-DD"
    static final String DATETIME_FMT = "${CALENDAR_YEAR}-MM-DD h:mma"
    static final String TIME_FMT = 'h:mma'
    static final String MONTH_DAY_FMT = 'MMM D'

    static boolean intervalElapsed(Long interval, Object lastRun) {
        if (!lastRun) return true
        Long lastRunMs = lastRun instanceof Date ? ((Date) lastRun).time : (Long) lastRun
        return System.currentTimeMillis() > lastRunMs + interval
    }
}
