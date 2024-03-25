/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.track

import io.xh.hoist.json.JSONFormat
import io.xh.hoist.util.Utils

import static io.xh.hoist.util.DateTimeUtils.appDay

class TrackLog implements JSONFormat {

    String username
    String category
    String msg
    String error
    String browser
    String device
    String userAgent
    String data
    String appVersion
    String appEnvironment
    String url
    Integer elapsed
    String severity
    boolean userAlerted = false
    Date dateCreated
    String impersonating

    static mapping = {
        table 'xh_track_log'
        cache true
        data type: 'text'
        dateCreated index: 'idx_xh_track_log_date_created'
    }

    static constraints = {
        msg(maxSize: 255, nullable: true)
        username(maxSize: 50)
        category(maxSize: 100)
        error(nullable: true)
        browser(nullable: true, maxSize: 100)
        device(nullable: true, maxSize: 100)
        userAgent(nullable: true)
        data(nullable: true, validator: { Utils.isJSON(it) ?: 'default.invalid.json.message'})
        appVersion(nullable: true, maxSize: 100)
        appEnvironment(nullable: true, maxSize: 100)
        url(nullable: true, maxSize: 500)
        elapsed(nullable: true)
        impersonating(nullable: true, maxSize: 50)
    }


    Map formatForJSON() {
        return [
                id: id,
                dateCreated: dateCreated,
                day: appDay(dateCreated),
                username: username,
                browser: browser,
                device: device,
                userAgent: userAgent,
                category: category,
                msg: msg,
                data: data,
                elapsed: elapsed,
                severity: severity,
                impersonating: impersonating,
                error         : error,
                appVersion    : appVersion,
                appEnvironment: appEnvironment,
                url           : url,
                userAlerted   : userAlerted,
        ]
    }

}
