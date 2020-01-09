/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2020 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.clienterror

import grails.events.*
import io.xh.hoist.BaseService
import io.xh.hoist.util.Utils
import org.grails.web.util.WebUtils

import static io.xh.hoist.browser.Utils.getBrowser
import static io.xh.hoist.browser.Utils.getDevice

class ClientErrorService extends BaseService implements EventPublisher {

    /**
     * Create a client exception entry. Username, browser info, environment info, and datetime will be set automatically.
     * @param message - optional comments supplied by the user
     * @param error - error generated by client-side error reporting
     * @param appVersion - expected from client to ensure we record the version user's browser is actually running
     */
    void submit(String message, String error, String appVersion, boolean userAlerted) {
        def request = WebUtils.retrieveGrailsWebRequest().currentRequest,
            userAgent = request?.getHeader('User-Agent'),
            idSvc = identityService,
            authUsername = idSvc.getAuthUser().username,
            values = [
                    msg: message,
                    error: error,
                    username: authUsername,
                    userAgent: userAgent,
                    browser: getBrowser(userAgent),
                    device: getDevice(userAgent),
                    appVersion: appVersion ?: Utils.appVersion,
                    appEnvironment: Utils.appEnvironment,
                    userAlerted: userAlerted
            ]

        ClientError.withNewSession {
            def ce = new ClientError(values)
            ce.save(flush: true)
            notify('xhClientErrorReceived', ce)
        }
    }

}
