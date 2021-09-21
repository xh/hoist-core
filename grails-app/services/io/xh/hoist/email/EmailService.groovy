/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2021 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.email

import io.xh.hoist.BaseService
import io.xh.hoist.util.Utils


/**
 * Service for sending email, controlled and managed via several soft configuration options for flexibility and safer
 * use in non-production environments, where careful limits could be required on sending mail to real users.
 *
 *      + xhEmailDefaultSender - address to use as default sender address if not specified in sendEmail() args.
 *      + xhEmailFilter - addresses to which the service is allowed to send mail (or "none" for no filtering).
 *      + xhEmailOverride - addresses to which the service will send all mail, regardless of sendEmail() args (or "none").
 */
class EmailService extends BaseService {

    def configService
    def groovyPageRenderer

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
     * To determine email body, either "text" OR "html" OR ("view" AND "model") must be included in args:
     *
     *      text {String}           - plain text message
     *      html {String}           - html message
     *      view                    - gsp file path
     *      model                   - model to be applied to the view
     */
    void sendEmail(Map args) {
        def logMsg = "",
            throwError = args.throwError

        try {
            def originalTo = args.to,
                sender = args.from ? formatAddresses(args.from)[0] : parseMailConfig('xhEmailDefaultSender')[0],
                doLog = args.containsKey('doLog') ? args.doLog : true,
                logIdentifier = args.logIdentifier ?: args.subject

            logMsg = "$sender -> ${originalTo.take(100)} | ${logIdentifier.take(70)}"

            def toRecipients = filterAddresses(formatAddresses(args.to)),
                ccRecipients = args.cc ? filterAddresses(formatAddresses(args.cc)) : null,
                env = Utils.appEnvironment.displayName.toUpperCase(),
                envString = Utils.isProduction ? '' : " [$env]",
                subj = args.subject + envString,
                isAsync = args.containsKey('async') ? args.async : false

            if (!toRecipients) {
                log.debug("No valid recipients found after filtering | ${logMsg}")
                return
            }

            def overrideEmail = parseMailConfig('xhEmailOverride')
            if (overrideEmail) {
                toRecipients = overrideEmail
                ccRecipients = null
                subj += " [for $originalTo]"
                logMsg += " | redirected to $toRecipients"
            }

            sendMail {
                async isAsync
                from sender
                to toRecipients.toArray()
                if (ccRecipients) {
                    cc ccRecipients.toArray()
                }
                subject subj.take(70)

                if (args.containsKey('html')) {
                    html args.html as String
                } else if (args.containsKey('text')) {
                    text args.text as String
                } else {
                    html groovyPageRenderer.render(
                            view: args.view,
                            model: args.model
                    )
                }

            }

            if (doLog) {
                log.info("Sent mail | $logMsg")
            }
        } catch (Exception e) {
            logErrorCompact("Error sending email $logMsg", e)
            if (throwError) throw e
        }
    }

    /**
     * Read a set of email addresses from a config.
     * See parseAddresses()
     */
    List<String> parseMailConfig(String configName) {
        def addresses = configService.getStringList(configName)
        return parseAddresses(addresses.join(','))
    }

    /**
     *  Parse a comma delimited list of email addresses into a list of trimmed,
     *  properly formatted email addresses. Contains special support for the string 'none'.
     *
     * @param configName
     * @return list of email addresses.
     */
    List<String> parseAddresses(String s) {
        return s == 'none' ? null : formatAddresses(s)
    }


    //------------------------
    // Implementation
    //------------------------
    private List<String> filterAddresses(Collection<String> rawEmails) {
        def filter = parseMailConfig('xhEmailFilter')
        if (filter) {
            rawEmails = filter.intersect(rawEmails)
            if (!rawEmails) return null
        }
        return rawEmails
    }

    private List<String> formatAddresses(Object o){
        def domain = configService.getString('xhEmailDefaultDomain')
        if (o instanceof String) o = o.split(',')
        return o.collect {String email ->
            email = email.trim()
            domain = domain.contains('@') ? domain : '@' + domain
            email.contains('@') ? email : (email + domain)
        }
    }
    
}

