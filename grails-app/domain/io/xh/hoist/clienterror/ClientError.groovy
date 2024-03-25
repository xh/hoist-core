/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.clienterror

import io.xh.hoist.json.JSONFormat
import io.xh.hoist.util.Utils

import static io.xh.hoist.util.DateTimeUtils.appDay

class ClientError implements JSONFormat {

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
        table 'xh_client_error'
        cache true
        error type: 'text'
        msg type: 'text'
        dateCreated index: 'idx_xh_client_error_date_created'

        // We will manually set dateCreated in ErrorService, which is bulk generating these
        autoTimestamp false
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
                id            : id,
                category      : category,
                severity      : severity,
                msg           : msg,
                error         : error,
                username      : username,
                userAgent     : userAgent,
                browser       : browser,
                device        : device,
                appVersion    : appVersion,
                appEnvironment: appEnvironment,
                url           : url,
                userAlerted   : userAlerted,
                dateCreated   : dateCreated,
                day           : appDay(dateCreated),
                data: data,
                elapsed: elapsed,
                impersonating: impersonating
        ]
    }

}
