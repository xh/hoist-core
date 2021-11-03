/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2021 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.clienterror

import io.xh.hoist.BaseService
import io.xh.hoist.json.JSONParser
import io.xh.hoist.json.JSONSerializer
import io.xh.hoist.util.Utils
import io.xh.hoist.util.StringUtils

import static io.xh.hoist.util.Utils.appName

class ClientErrorEmailService extends BaseService {

    def emailService

    void sendMail(Collection<Map> errors, boolean maxErrorsReached) {
        def to = emailService.parseMailConfig('xhEmailSupport')
        if (to) {
            def count = errors.size(),
                single = count == 1,
                subject = single ?
                    "$appName Client Exception" :
                    "$appName Client Exceptions ($count${maxErrorsReached ? '+' : ''}) ",
                html = single ? formatSingle(errors.first()) : formatDigest(errors)

            emailService.sendEmail(async: true, to: to, subject: subject, html: html)
        }
    }

    //------------------
    // Implementation
    //------------------
    private String formatSingle(Map ce, boolean withDetails = true) {
        def parts = [],
            errorText = ce.error,
            errorJSON = safeParseJSON(errorText)

        def errorSummary = errorJSON ? errorJSON.message : errorText
        errorSummary = errorSummary ? StringUtils.elide(errorSummary, 80) : 'Client Error'

        def metaText = [
                "Error: ${errorSummary}",
                "User: ${ce.username}",
                "App: ${appName} (${Utils.appCode})",
                "Version: ${ce.appVersion}",
                "Environment: ${ce.appEnvironment}",
                "Browser: ${ce.browser}",
                "Device: ${ce.device}",
                "URL: ${ce.url}",
                "Time: ${ce.dateCreated.format('dd-MMM-yyyy HH:mm:ss')}"
        ].join('<br/>')

        parts << metaText
        parts << ce.userMessage
        if (withDetails) {
            parts << (errorJSON ? '<pre>' + JSONSerializer.serializePretty(errorJSON) + '</pre>' : errorText)
        }
        return parts.findAll().join('<br/><br/>')
    }

    private String formatDigest(Collection<Map> msgs) {
        return msgs
                .sort { -it.dateCreated.date }
                .collect { this.formatSingle(it, false) }
                .join('<br/><br/><hr/><br/>')
    }

    private Map safeParseJSON(String errorText) {
        try {
            return JSONParser.parseObject(errorText)
        } catch (Exception ignored) {
            return null
        }
    }
}
