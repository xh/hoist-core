/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2020 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.monitor

import io.xh.hoist.BaseService
import io.xh.hoist.util.Utils

import static io.xh.hoist.monitor.MonitorStatus.WARN

/**
 * Listens for status monitor change events from MonitoringService and emails status updates to
 * a configurable list of recipients.
 */
class MonitoringEmailService extends BaseService {

    def emailService

    void init() {
        subscribe('xhMonitorStatusReport', this.&emailReport)
    }
    
    //------------------------
    // Implementation
    //------------------------
    private void emailReport(MonitorStatusReport report) {
        def to = emailService.parseMailConfig('xhMonitorEmailRecipients')
        if (to) {
            emailService.sendEmail(
                to: to,
                subject: report.title,
                html: formatHtml(report),
                async: true
            )
        }
    }

    private String formatHtml(MonitorStatusReport report) {
        def results = report.results

        results.sort{it.name}
        results.sort{it.status}

        if (report.status < WARN) return "There are no alerting monitors for ${Utils.appName}."

        return results.findAll{it.status >= WARN}.collect {
            "+ $it.name | ${it.message ? it.message + ' | ' : ''}Minutes in [${it.status}]: ${it.minsInStatus}"
        }.join('<br>')
    }
    
}
