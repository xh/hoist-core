/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2018 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.feedback

import io.xh.hoist.json.JSONFormat
import io.xh.hoist.util.Utils

class Feedback implements JSONFormat {

    String msg
    String username
    String userAgent
    String browser
    String device
    String appVersion
    String appEnvironment
    boolean sent = true  // TODO - remove
    Date dateCreated

    static mapping = {
        table 'xh_feedback'
        cache true
        msg type: 'text'
    }

    static constraints = {
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
