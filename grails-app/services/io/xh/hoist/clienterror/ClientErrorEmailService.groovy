/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2018 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.clienterror

import io.xh.hoist.BaseService
import io.xh.hoist.util.Utils

class ClientErrorEmailService extends BaseService {

    def emailService

    void init() {
        subscribeWithSession('xhClientErrorReceived', this.&emailClientException)
        super.init()
    }


    //-------------------------
    // Implementation
    //-------------------------
    private void emailClientException(ClientError ce) {
        def to = emailService.parseMailConfig('xhEmailSupport'),
            subject = "${Utils.appName.capitalize()} feedback"
        if (to) {
            emailService.sendEmail(async: true, to: to, subject: subject, html: formatHtml(ce))
        }
    }

    private String formatHtml(ClientError ce) {
        def msgText = ce.msg,
            errorText = ce.error,
            metaText = [
                    "User: ${ce.username}",
                    "App: ${Utils.appName}",
                    "Version: ${ce.appVersion}",
                    "Environment: ${ce.appEnvironment}",
                    "Browser: ${ce.browser}",
                    "Device: ${ce.device}",
                    "Submitted: ${ce.dateCreated.format('dd-MMM-yyyy HH:mm:ss')}"
            ].join('<br/>')

        return [msgText, errorText, metaText]
                .findAll {it}
                .join('<br/><br/>')
    }

}
