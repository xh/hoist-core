/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.feedback

import grails.gorm.transactions.Transactional
import io.xh.hoist.BaseService
import io.xh.hoist.util.Utils
import static io.xh.hoist.util.Utils.getCurrentRequest
import static io.xh.hoist.browser.Utils.getBrowser
import static io.xh.hoist.browser.Utils.getDevice

class FeedbackService extends BaseService {

    /**
     * Create a feedback entry. Username, browser + environment info, and datetime will be set automatically.
     * @param message - comments supplied by the user
     * @param appVersion - expected from client to ensure we record the version user's browser is actually running
     */
    @Transactional
    void submit(String message, String appVersion) {
        def request = currentRequest,
            userAgent = request?.getHeader('User-Agent'),
            values = [
                msg           : message,
                username      : authUsername ?: 'ANON',
                userAgent     : userAgent,
                browser       : getBrowser(userAgent),
                device        : getDevice(userAgent),
                appVersion    : appVersion ?: Utils.appVersion,
                appEnvironment: Utils.appEnvironment
            ]
        def fb = new Feedback(values)
        fb.save(flush: true)
        getTopic('xhFeedbackReceived').publishAsync(fb)
    }
}
