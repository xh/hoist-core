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
import io.xh.hoist.util.Utils

import static io.xh.hoist.util.DateTimeUtils.formatDate
import static io.xh.hoist.util.Utils.appName

/**
 * Service to broadcast end user feedback posted to {@link TrackService} via email.
 */
@CompileStatic
class FeedbackEmailService extends BaseService {

    EmailService emailService

    private List<String> getToAddress() { emailService.parseMailConfig('xhEmailSupport') }

    private int emailsSent = 0

    void init() {
        subscribeToTopic(
            topic: 'xhTrackReceived',
            onMessage: { TrackLog tl ->
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
            subject = "$appName User Feedback"

        if (to) {
            logInfo('Sending user feedback email', [from: tl.username, to: to])
            emailService.sendEmail(async: true, to: to, subject: subject, html: formatHtml(tl))
            emailsSent++
        }
    }

    private String formatHtml(TrackLog tl) {
        def msgText = tl.dataAsObject?.userMessage,
            metaText = [
                "User: ${tl.username}",
                "App: ${appName} (${Utils.appCode})",
                "Version: ${tl.appVersion}",
                "Environment: ${tl.appEnvironment}",
                "Browser: ${tl.browser}",
                "Device: ${tl.device}",
                "Submitted: ${formatDate(tl.dateCreated,'dd-MMM-yyyy HH:mm:ss')}"
            ].join('<br/>')

        return [msgText, metaText].findAll().join('<br/><br/>')
    }

    Map getAdminStats() {
        [
            config: [
                toAddress: toAddress
            ],
            emailsSent: emailsSent
        ]
    }

}
