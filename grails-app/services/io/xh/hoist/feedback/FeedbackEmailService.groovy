/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2020 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.feedback

import io.xh.hoist.BaseService
import io.xh.hoist.util.Utils

class FeedbackEmailService extends BaseService {

    def emailService

    void init() {
        subscribe('xhFeedbackReceived', this.&emailFeedback)
    }

    //------------------------
    // Implementation
    //------------------------
    private void emailFeedback(Feedback fb) {
        def to = emailService.parseMailConfig('xhEmailSupport'),
            subject = "${Utils.appName} feedback"
        
        if (to) {
            emailService.sendEmail(async: true, to: to, subject: subject, html: formatHtml(fb))
        }
    }

    private String formatHtml(Feedback fb) {
        def msgText = fb.msg,
            metaText = [
                    "User: ${fb.username}",
                    "App: ${Utils.appName} (${Utils.appCode})",
                    "Version: ${fb.appVersion}",
                    "Environment: ${fb.appEnvironment}",
                    "Browser: ${fb.browser}",
                    "Device: ${fb.device}",
                    "Submitted: ${fb.dateCreated.format('dd-MMM-yyyy HH:mm:ss')}"
            ].join('<br/>')

        return [msgText, metaText].findAll{it}.join('<br/><br/>')
    }
    
}
