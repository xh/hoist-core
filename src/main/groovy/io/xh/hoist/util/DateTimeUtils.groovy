/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2018 Extremely Heavy Industries Inc.
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

    static boolean intervalElapsed(Long interval, Object lastRun) {
        if (!lastRun) return true
        Long lastRunMs = lastRun instanceof Date ? ((Date) lastRun).time : (Long) lastRun
        return System.currentTimeMillis() > lastRunMs + interval
    }
}
