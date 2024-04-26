/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.monitor

import io.xh.hoist.BaseService
import io.xh.hoist.util.Utils

import static io.xh.hoist.monitor.MonitorStatus.WARN
import static io.xh.hoist.util.DateTimeUtils.MINUTES
import static io.xh.hoist.util.DateTimeUtils.intervalElapsed
import static java.lang.System.currentTimeMillis

/**
 * Listens for status monitor change events from MonitoringService and generates a report.
 * Reports generated periodically, and also when status changes after certain thresholds.
 *
 * Also emails status updates to a configurable list of recipients.
 */
class MonitorReportService extends BaseService {

    def emailService,
        configService

    // Notification state for primary instance to manage
    // If primary instance goes down, may get extra notification -- that's ok
    private Long lastNotified = null
    private boolean alertMode = false

    void noteResultsUpdated(List<MonitorResults> results) {
       if (!isPrimary) return;

        def failThreshold = config.failNotifyThreshold,
            warnThreshold = config.warnNotifyThreshold

        // 1) Calc new alert mode, true if crossed thresholds or already alerting and still have problems
        boolean newAlertMode = (alertMode && results?.any {it.status >= WARN})  ||
            results?.any { it.cyclesAsFail >= failThreshold || it.cyclesAsWarn >= warnThreshold }

        // 2) Generate report if we have a change, or still alerting and interval has elapsed
        if (newAlertMode != alertMode ||
            (newAlertMode && intervalElapsed(config.monitorRepeatNotifyMins * MINUTES, lastNotified))
        ) {
            lastNotified = currentTimeMillis()
            alertMode = newAlertMode
            generateStatusReport(results)
        }
    }

    //------------------------
    // Implementation
    //------------------------
    private MonitorStatusReport generateStatusReport(List<MonitorResults> results) {
        def report = new MonitorStatusReport(results)
        logDebug("Emitting monitor status report: ${report.title}")
        getTopic('xhMonitorStatusReport').publishAsync(report)
        emailReport(report)
    }

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

        def problems = report.results.findAll {it.status >= WARN}

        if (!problems) return "There are no alerting monitors for ${Utils.appName}."

        return problems
            .sort {it.name}
            .sort {it.status }
            .collect {
                "+ ${it.name} | ${it.message ? it.message + ' | ' : ''}Minutes in [${it.status}]: ${it.minsInStatus}"
            }.join('<br>')
    }

    private Map getConfig() {
        configService.getMap('xhMonitorConfig')
    }

    Map getAdminStats() {[
        config: [
            toAddress: emailService.parseMailConfig('xhMonitorEmailRecipients'),
            *: configService.getForAdminStats('xhMonitorConfig')
        ],
        lastNotifed: lastNotified,
        alertMode: alertMode
    ]}

}
