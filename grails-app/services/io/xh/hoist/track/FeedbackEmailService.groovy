/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2025 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.track

import groovy.transform.CompileStatic
import io.xh.hoist.BaseService
import io.xh.hoist.email.EmailService
import io.xh.hoist.json.JSONParser
import io.xh.hoist.util.Utils

@CompileStatic
class FeedbackEmailService extends BaseService {

    EmailService emailService

    private String getToAddress()   { emailService.parseMailConfig('xhEmailSupport')}

    void init() {
        subscribeToTopic(
            topic: 'xhTrackReceived',
            onMessage: {TrackLog tl ->
                if (tl.category == 'Feedback') emailFeedback(tl)
            },
            primaryOnly: true
        )
    }

    //------------------------
    // Implementation
    //------------------------
    private void emailFeedback(TrackLog tl) {
        def to = toAddress,
            subject = "${Utils.appName} feedback"
        logInfo('Forwarding feedback email', [from: tl.username, to: to])
        if (to) {
            emailService.sendEmail(async: true, to: to, subject: subject, html: formatHtml(tl))
        }
    }

    private String formatHtml(TrackLog tl) {
        def msgText = tl.dataAsObject?.userMessage,
            metaText = [
                    "User: ${tl.username}",
                    "App: ${Utils.appName} (${Utils.appCode})",
                    "Version: ${tl.appVersion}",
                    "Environment: ${tl.appEnvironment}",
                    "Browser: ${tl.browser}",
                    "Device: ${tl.device}",
                    "Submitted: ${tl.dateCreated.format('dd-MMM-yyyy HH:mm:ss')}"
            ].join('<br/>')

        return [msgText, metaText].findAll().join('<br/><br/>')
    }

    Map getAdminStats() {[
        config: [toAddress: toAddress]
    ]}

}
