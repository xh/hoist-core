/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2018 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.clientexception

import io.xh.hoist.json.JSONFormat

class ClientException implements JSONFormat {

    String msg
    String error
    String username
    String userAgent
    String browser
    String device
    String appVersion
    String appEnvironment
    Date dateCreated

    static mapping = {
        table 'xh_client_exceptions'
        cache true
        error type: 'text'
        msg type: 'text'
    }

    static constraints = {
        msg(nullable: true)
        error(nullable: true)
        username(maxSize: 50)
        browser(nullable: true, maxSize: 100)
        device(nullable: true, maxSize: 100)
        userAgent(nullable: true)
        appVersion(nullable: true, maxSize: 100)
        appEnvironment(nullable: true, maxSize: 100)
    }

    Map formatForJSON() {
        return [
                id: id,
                msg: msg,
                error: error,
                username: username,
                userAgent: userAgent,
                browser: browser,
                device: device,
                appVersion: appVersion,
                appEnvironment: appEnvironment,
                dateCreated: dateCreated
        ]
    }
    
}
