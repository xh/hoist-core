/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.clienterror

import io.xh.hoist.json.JSONFormat

import static io.xh.hoist.util.DateTimeUtils.appDay

class ClientError implements JSONFormat {

    String correlationId
    String username
    String msg
    String error
    String browser
    String device
    String userAgent
    String appVersion
    String appEnvironment
    String url
    String instance
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
        correlationId(nullable: true, maxSize: 100)
        msg(nullable: true)
        username(maxSize: 50)
        error(nullable: true)
        browser(nullable: true, maxSize: 100)
        device(nullable: true, maxSize: 100)
        userAgent(nullable: true)
        appVersion(nullable: true, maxSize: 100)
        appEnvironment(nullable: true, maxSize: 100)
        url(nullable: true, maxSize: 500)
        instance(nullable: true, maxSize: 50)
        impersonating(nullable: true, maxSize: 50)
    }

    Map formatForJSON() {
        return [
                id            : id,
                correlationId : correlationId,
                msg           : msg,
                error         : error,
                username      : username,
                userAgent     : userAgent,
                browser       : browser,
                device        : device,
                appVersion    : appVersion,
                appEnvironment: appEnvironment,
                url           : url,
                instance      : instance,
                userAlerted   : userAlerted,
                dateCreated   : dateCreated,
                day           : appDay(dateCreated),
                impersonating: impersonating
        ]
    }

}
