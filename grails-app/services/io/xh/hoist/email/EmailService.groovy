/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2025 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.email

import io.xh.hoist.BaseService
import io.xh.hoist.util.Utils


/**
 * Service for sending email, controlled and managed via several soft configuration options for flexibility and safer
 * use in non-production environments, where careful limits could be required on sending mail to real users.
 *
 *  + xhEmailDefaultSender - address to use as default sender address if not specified in sendEmail() args.
 *  + xhEmailFilter - addresses to which the service is allowed to send mail (or "none" for no filtering).
 *  + xhEmailOverride - addresses to which the service will send all mail, regardless of sendEmail() args (or "none").
 */
class EmailService extends BaseService {

    def configService

    private Date lastSentDate = null
    private Long emailsSent = 0

    /**
     * Send email as specified by args param.
     * Application environment will be appended to subject unless environment is Production.
     *
     * @param args
     *      to {String or List}     - required, email address(es) of recipient(s)
     *      from {String}           - optional, sender address, defaults to config
     *      cc {String or List}     - optional, cc recipients;
     *      subject {String}        - optional
     *      async {Boolean}         - option to send email asynchronously, defaults to false
     *      doLog {Boolean}         - option to log email information on send, defaults to true
     *      logIdentifier {String}  - optional, string to append to log message, defaults to subject
     *      throwError {Boolean}    - option to throw on error or to suppress and log, defaults to false
     *
     * To determine email body, either "text" OR "html" must be included in args:
     *
     *      text {String}           - plain text message
     *      html {String}           - html message
     *
     * To attach file(s) to the email, include:
     *
     *      attachments {Map or List<Map>}
     *             fileName {String}
     *             contentType {String}
     *             contentSource {byte[] or File or InputStreamSource}
     */
    void sendEmail(Map args) {
        def logMsg = "",
            throwError = args.throwError

        try {
            // 1) Pre-process and normalize inputs and configs
            def override = parseMailConfig('xhEmailOverride'),
                filter = parseMailConfig('xhEmailFilter'),
                toSpec = filterAddresses(formatAddresses(args.to), filter),
                ccSpec = filterAddresses(formatAddresses(args.cc), filter)

            List<String> toUse = override ? override : toSpec
            List<String> ccUse = override ? [] : ccSpec
            String fromUse = args.from ? formatAddresses(args.from)[0] : parseMailConfig('xhEmailDefaultSender')[0]
            String subjectUse = args.subject ?: ''
            List<Map> attachments = parseAttachments(args.attachments)
            boolean hasAttachments = args.attachments as boolean
            boolean doLog = args.containsKey('doLog') ? args.doLog : true

            logMsg = createLogMsg(args, fromUse, filter, override)

            // 2) Early outs
            if (Utils.isLocalDevelopment && !override && !filter) {
                logInfo(
                    'No emails sent',
                    'emailing from local development requires an active xhEmailOverride or xhEmailFilter config',
                    logMsg
                )
                return
            }
            if (!toUse || !toSpec) {
                logDebug('No emails sent', 'no valid recipients found after filtering', logMsg)
                return
            }

            // 3) Enhance subject with context
            def devContext = []
            if (!Utils.isProduction) {
                devContext << Utils.appEnvironment.displayName.toUpperCase();
            }
            if (override) {
                devContext << (toSpec.size() > 1 ? "for ${toSpec.size()} receipients" : "for ${toSpec.first()}")
            }
            if (devContext) {
                subjectUse += " [${devContext.join(', ')}]"
            }

            // 4) Send email!
            sendMail {
                multipart hasAttachments
                async(args.async as boolean)
                from fromUse
                to toUse.toArray()
                if (ccUse) {
                    cc ccUse.toArray()
                }
                subject subjectUse.take(255)

                if (args.containsKey('html')) {
                    html args.html as String
                } else if (args.containsKey('text')) {
                    text args.text as String
                } else {
                    throw new RuntimeException("Must provide 'html' or 'text' for email.")
                }

                attachments.each { Map f ->
                    attach f.fileName as String, f.contentType as String, f.contentSource
                }
            }
            emailsSent++
            lastSentDate = new Date()

            if (doLog) {
                logInfo('Sent mail', logMsg)
            }

        } catch (Exception e) {
            logError('Error sending email', logMsg, e)
            if (throwError) throw e
        }
    }

    /**
     * Read a set of email addresses from an app config, normalizing as per {@link #parseAddresses}.
     */
    List<String> parseMailConfig(String configName) {
        def addresses = configService.getStringList(configName)
        return parseAddresses(addresses.join(','))
    }

    /**
     *  Parse a comma delimited list of email addresses into a list of trimmed, properly
     *  formatted addresses, appending the xhEmailDefaultDomain config to any unqualified address.
     *
     *  Includes special support for returning null if given the string 'none', to allow for
     *  sourcing optional / potentially-empty addresses from string configs.
     */
    List<String> parseAddresses(String s) {
        return s == 'none' ? null : formatAddresses(s)
    }

    Map getAdminStats() {
        [
            config      : configForAdminStats(
                'xhEmailOverride',
                'xhEmailFilter',
                'xhEmailDefaultSender',
                'xhEmailDefaultDomain'
            ),
            emailsSent  : emailsSent,
            lastSentDate: lastSentDate
        ]
    }

    //------------------------
    // Implementation
    //------------------------
    private List<String> filterAddresses(Collection<String> rawEmails, List<String> filter) {
        filter ? filter.intersect(rawEmails) : rawEmails
    }

    private List<String> formatAddresses(Object o) {
        if (o instanceof String) o = o.split(',')
        if (!o) return []

        String defaultDomain = configService.getString('xhEmailDefaultDomain')
        if (!defaultDomain.startsWith('@')) defaultDomain = '@' + defaultDomain

        return o.collect { String email ->
            email = email.trim()
            email.contains('@') ? email : (email + defaultDomain)
        }
    }

    private List<Map> parseAttachments(Object attachments) {
        attachments ? (attachments instanceof Map ? [attachments] : attachments) : []
    }

    private Map createLogMsg(Map args, String fromUse, List<String> filters, List<String> override) {
        String logIdentifier = args.logIdentifier ?: args.subject ?: '[No Subject]'
        def ret = [
            _id : logIdentifier.take(70),
            from: fromUse,
            to  : args.to?.toString()?.take(255)
        ]
        if (override) {
            ret.redirectedTo = override
        } else if (filters) {
            ret.filtered = true
        }

        return ret
    }
}

