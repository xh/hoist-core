/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2025 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.track

import grails.gorm.transactions.ReadOnly
import io.xh.hoist.BaseService
import io.xh.hoist.config.ConfigService
import io.xh.hoist.email.EmailService
import io.xh.hoist.util.DateTimeUtils
import io.xh.hoist.util.Utils

import static io.xh.hoist.json.JSONSerializer.serializePretty
import static io.xh.hoist.util.Utils.appName
import io.xh.hoist.util.Timer

/**
 * Service to broadcast client error reports posted to {@link TrackService} via email. Queries for
 * recent reports on a timer and will summarize multiple errors into a single digest-style mail.
 */
class ClientErrorEmailService extends BaseService {

    ConfigService configService
    EmailService emailService

    private int getAlertInterval() {
        configService.getMap('xhClientErrorConfig').intervalMins as Integer * DateTimeUtils.MINUTES
    }

    private String getToAddress() { emailService.parseMailConfig('xhEmailSupport') }

    private Timer timer
    private int emailsSent = 0

    void init() {
        super.init()
        timer = createTimer(
            name: 'processErrors',
            runFn: this.&processErrors,
            interval: { alertInterval },
            delay: 15 * DateTimeUtils.SECONDS,
            primaryOnly: true
        )
    }

    //------------------
    // Implementation
    //------------------
    @ReadOnly
    private void processErrors() {
        if (!timer.lastRunCompleted) return

        List errors = TrackLog.findAllByDateCreatedGreaterThanEqualsAndCategory(
            new Date(timer.lastRunCompleted),
            'Client Error'
        )

        if (errors) {
            logInfo("Emailing recent client errors", [count: errors.size()])
            sendErrorMail(errors)
        }
    }

    //-----------------
    // Implementation
    //-----------------
    private void sendErrorMail(List<TrackLog> errors) {
        def to = toAddress
        if (to) {
            def count = errors.size(),
                single = count == 1,
                subject = single ?
                    "$appName Client Error" :
                    "$appName Client Errors ($count)",
                html = single ? formatSingle(errors.first()) : formatDigest(errors)

            logInfo('Sending error report email', [errorCount: count, to: to])
            emailService.sendEmail(async: true, to: to, subject: subject, html: html)
            emailsSent++
        }
    }

    private String formatSingle(TrackLog tl, boolean withDetails = true) {
        def parts = [],
            dataObj = tl.dataAsObject

        def metaText = [
            "Error: ${tl.errorSummary}",
            "User: ${tl.username}" + (tl.impersonating ? " (as ${tl.impersonating})" : ''),
            "App: ${appName} (${Utils.appCode})",
            "Version: ${tl.appVersion}",
            "Environment: ${tl.appEnvironment}",
            "Browser: ${tl.browser}",
            "Device: ${tl.device}",
            "URL: ${tl.url}",
            tl.correlationId ? "Correlation ID: ${tl.correlationId}" : null,
            "Time: ${tl.dateCreated.format('dd-MMM-yyyy HH:mm:ss')}"
        ].findAll().join('<br/>')

        parts << metaText
        parts << dataObj?.userMessage
        if (withDetails) {
            parts << (dataObj?.error ? "<pre>${serializePretty(dataObj.error)}</pre>" : null)
        }
        return parts.findAll().join('<br/><br/>')
    }

    private String formatDigest(Collection<TrackLog> msgs) {
        return msgs
            .sort { it.dateCreated }
            .reverse()
            .collect { formatSingle(it, false) }
            .join('<br/><br/><hr/><br/>')
    }

    Map getAdminStats() {
        [
            config: [
                toAddress    : toAddress,
                alertInterval: alertInterval
            ],
            emailsSent: emailsSent
        ]
    }

    void clearCaches() {
        if (isPrimary) timer.forceRun()
    }
}
