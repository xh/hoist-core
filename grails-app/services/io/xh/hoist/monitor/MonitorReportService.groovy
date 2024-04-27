/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.monitor

import grails.compiler.GrailsCompileStatic
import io.xh.hoist.BaseService
import io.xh.hoist.config.ConfigService
import io.xh.hoist.email.EmailService

import static io.xh.hoist.monitor.MonitorStatus.WARN
import static io.xh.hoist.util.DateTimeUtils.MINUTES
import static io.xh.hoist.util.DateTimeUtils.intervalElapsed
import static java.lang.System.currentTimeMillis

/**
 * Listens for status monitor change events from `MonitoringService` and generates a
 * {@link MonitorStatusReport} based on the latest results across all instances.
 *
 * The generated report is published on the `xhMonitorStatusReport` topic for consumption by
 * app-specific alerting (e.g. a custom service to send alerts to an external monitoring system).
 *
 * If so configured, this service will also send generated reports to a list of email recipients.
 *
 * Reports are generated whenever status changes, respecting configurable thresholds to confirm
 * a status change and avoid "flapping". Reports are also (re)generated at a regular interval while
 * monitors are in a non-OK state.
 */
@GrailsCompileStatic
class MonitorReportService extends BaseService {

    ConfigService configService
    EmailService emailService

    // Notification state for primary instance to manage. Not replicated as non-critical -
    // if primary changes, worst that happens is an extra notification is sent early.
    private Long lastNotified = null
    private boolean alertMode = false

    void noteResultsUpdated(List<AggregateMonitorResult> results) {
       if (!isPrimary) return;

        def config = this.getConfig(),
            failThreshold = config.failNotifyThreshold,
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
            generateAndPublishReport(results)
        }
    }

    //------------------------
    // Implementation
    //------------------------
    private void generateAndPublishReport(List<AggregateMonitorResult> results) {
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
                html: report.toHtml(),
                async: true
            )
        }
    }

    private MonitorConfig getConfig() {
        new MonitorConfig(configService.getMap('xhMonitorConfig'))
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
