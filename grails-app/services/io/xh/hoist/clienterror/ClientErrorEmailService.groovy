/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2020 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.clienterror

import io.xh.hoist.BaseService
import io.xh.hoist.json.JSONParser
import io.xh.hoist.json.JSONSerializer
import io.xh.hoist.util.Utils
import io.xh.hoist.util.StringUtils

class ClientErrorEmailService extends BaseService {

    def emailService

    void init() {
        subscribe('xhClientErrorReceived', this.&emailClientException)
    }

    //-------------------------
    // Implementation
    //-------------------------
    private void emailClientException(ClientError ce) {
        def to = emailService.parseMailConfig('xhEmailSupport'),
            subject = "${Utils.appName} Client Exception"
        if (to) {
            emailService.sendEmail(async: true, to: to, subject: subject, html: formatHtml(ce))
        }
    }

    private String formatHtml(ClientError ce) {
        def userMessage = ce.msg,
            errorText = ce.error,
            errorJSON = safeParseJSON(errorText)

        def errorSummary = errorJSON ? errorJSON.message : errorText
        errorSummary = errorSummary ? StringUtils.elide(errorSummary, 80) : 'Client Error'

        def errorFull = errorJSON ?  '<pre>' + JSONSerializer.serializePretty(errorJSON) + '</pre>'  : errorText;

        def metaText = [
                "Error: ${errorSummary}",
                "User: ${ce.username}",
                "App: ${Utils.appName} (${Utils.appCode})",
                "Version: ${ce.appVersion}",
                "Environment: ${ce.appEnvironment}",
                "Browser: ${ce.browser}",
                "Device: ${ce.device}",
                "Time: ${ce.dateCreated.format('dd-MMM-yyyy HH:mm:ss')}"
            ].join('<br/>')

        return [metaText, userMessage, errorFull]
                .findAll {it}
                .join('<br/><br/>')
    }


    private Map safeParseJSON(String errorText) {
        try {
            return JSONParser.parseObject(errorText)
        } catch (Exception ignored) {
            return null
        }
    }


}
