/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2025 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.track

import io.xh.hoist.json.JSONFormat
import io.xh.hoist.util.Utils

import static io.xh.hoist.util.DateTimeUtils.appDay

class TrackLog implements JSONFormat {

    // End user info
    String username
    String impersonating

    // Core tracking data
    Date dateCreated
    String category
    String msg
    String data
    Integer elapsed
    String severity

    // Identifiers
    String correlationId
    String loadId
    String tabId
    String instance

    // Client browser info
    String browser
    String device
    String userAgent

    // Client app info
    String appVersion
    String appEnvironment
    String url

    static mapping = {
        table 'xh_track_log'
        cache true
        data type: 'text'
        dateCreated index: 'idx_xh_track_log_date_created'

        // TrackService sets dateCreated explicitly, to match actual time tracked on client.
        autoTimestamp false
    }

    static cache = {
        evictionConfig.size = 20000
    }

    static constraints = {
        username(maxSize: 50)
        impersonating(nullable: true, maxSize: 50)

        category(maxSize: 100)
        msg(maxSize: 255)
        data(nullable: true, validator: { Utils.isJSON(it) ?: 'default.invalid.json.message' })
        elapsed(nullable: true)

        correlationId(nullable: true, maxSize: 100)
        loadId(nullable: true, maxSize: 8)
        tabId(nullable: true, maxSize: 8)
        instance(nullable: true, maxSize: 50)

        browser(nullable: true, maxSize: 100)
        device(nullable: true, maxSize: 100)
        userAgent(nullable: true)

        appVersion(nullable: true, maxSize: 100)
        appEnvironment(nullable: true, maxSize: 100)
        url(nullable: true, maxSize: 500)
    }

    Map formatForJSON() {
        return [
            id            : id,
            username      : username,
            impersonating : impersonating,
            dateCreated   : dateCreated,
            day           : appDay(dateCreated),
            category      : category,
            msg           : msg,
            data          : data,
            elapsed       : elapsed,
            severity      : severity,
            correlationId : correlationId,
            loadId        : loadId,
            tabId         : tabId,
            instance      : instance,
            browser       : browser,
            device        : device,
            userAgent     : userAgent,
            appVersion    : appVersion,
            appEnvironment: appEnvironment,
            url           : url
        ]
    }

}
