/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */
package io.xh.hoist.email

import grails.plugins.mail.MailMessageBuilder
import groovy.transform.CompileStatic
import groovy.transform.NamedParam
import groovy.transform.NamedVariant
import io.xh.hoist.BaseService
import io.xh.hoist.config.AppConfig
import io.xh.hoist.config.ConfigService
import io.xh.hoist.util.Utils
import org.springframework.core.io.InputStreamSource

/**
 * Service for sending email, controlled and managed via several soft configuration options for flexibility and safer
 * use in non-production environments, where careful limits could be required on sending mail to real users.
 *
 *  + xhEmailDefaultSender - address to use as default sender address if not specified in sendEmail() args.
 *  + xhEmailFilter - addresses to which the service is allowed to send mail (or "none" for no filtering).
 *  + xhEmailOverride - addresses to which the service will send all mail, regardless of sendEmail() args (or "none").
 */
@CompileStatic
class EmailService extends BaseService {

    ConfigService configService

    private Date lastSentDate = null
    private long emailsSent = 0

    /**
     * Send an email, with recipients, content, and delivery options given as named arguments.
     *
     * Requires a `to` address, plus one of `html` or `text` for the body. All recipients are
     * subject to the `xhEmailFilter` and `xhEmailOverride` configs, and the application
     * environment is appended to the subject unless running in Production.
     *
     * See `docs/email.md` for configuration details and examples.
     *
     * @param to             address(es) of recipient(s), as a String (comma-delimited) or
     *                       {@code List<String>}
     * @param cc             address(es) to copy, in the same forms accepted by `to`
     * @param bcc            address(es) to blind copy, in the same forms accepted by `to`
     * @param from           sender address, defaulting to the `xhEmailDefaultSender` config
     * @param subject        subject line, truncated to 255 chars after any environment context
     *                       has been appended
     * @param html           HTML message body. Provide either this or `text`.
     * @param text           plain-text message body. Provide either this or `html`.
     * @param attachments    file attachment(s), as a single Map or {@code List<Map>}. Each Map
     *                       requires `fileName` and `contentType` Strings, plus a `contentSource`
     *                       of byte[], File, or InputStreamSource.
     * @param markImportant  true to mark the email as important via standard headers
     * @param async          true to send asynchronously, without blocking on the SMTP round-trip
     * @param doLog          false to suppress the info-level message logged on a successful send
     * @param logIdentifier  identifier to log in place of the subject, defaulting to `subject`
     * @param throwError     true to rethrow any send failure. Default false to log and suppress.
     */
    @NamedVariant
    void sendEmail(

        // Recipients and sender
        @NamedParam(required = true) Object to,
        @NamedParam Object cc = null,
        @NamedParam Object bcc = null,
        @NamedParam String from = null,

        // Content - one of `html` or `text` is required
        @NamedParam String subject = null,
        @NamedParam String html = null,
        @NamedParam String text = null,
        @NamedParam Object attachments = null,

        // Delivery and logging options
        @NamedParam boolean markImportant = false,
        @NamedParam boolean async = false,
        @NamedParam boolean doLog = true,
        @NamedParam String logIdentifier = null,
        @NamedParam boolean throwError = false
    ) {
        Map logMsg = [:]

        try {
            // 1) Pre-process and normalize inputs and configs
            List<String> override = parseMailConfig('xhEmailOverride'),
                filter = parseMailConfig('xhEmailFilter'),
                toSpec = filterAddresses(formatAddresses(to), filter),
                ccSpec = filterAddresses(formatAddresses(cc), filter),
                bccSpec = filterAddresses(formatAddresses(bcc), filter)

            List<String> toUse = override ?: toSpec
            List<String> ccUse = override ? [] : ccSpec
            List<String> bccUse = override ? [] : bccSpec

            List<String> fromSpec = from ? formatAddresses(from) : parseMailConfig('xhEmailDefaultSender')
            String fromUse = fromSpec ? fromSpec.first() : null
            if (!fromUse) {
                throw new RuntimeException("Must provide a 'from' address, or a valid xhEmailDefaultSender config.")
            }

            String subjectUse = subject ?: ''
            List<Map> attachmentsUse = parseAttachments(attachments)
            boolean hasAttachments = attachmentsUse as boolean

            logMsg = createLogMsg(logIdentifier, subject, to, fromUse, filter, override)

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
            List<String> devContext = []
            if (!Utils.isProduction) {
                devContext << Utils.appEnvironment.displayName.toUpperCase()
            }
            if (override) {
                devContext << (toSpec.size() > 1 ? "for ${toSpec.size()} recipients" : "for ${toSpec.first()}").toString()
            }
            if (devContext) {
                subjectUse += " [${devContext.join(', ')}]"
            }

            // 4) Send email!
            sendMail {
                // Builder bound explicitly - named params above shadow its same-named DSL methods.
                MailMessageBuilder msg = delegate as MailMessageBuilder

                msg.multipart hasAttachments
                msg.async async
                msg.from fromUse
                msg.to toUse
                if (ccUse) {
                    msg.cc ccUse
                }
                if (bccUse) {
                    msg.bcc bccUse
                }
                if (markImportant) {
                    msg.headers (
                        'Importance': 'High',
                        'X-MSMail-Priority': 'High',
                        'X-Priority': 1
                    )
                }

                msg.subject subjectUse.take(255)

                if (html != null) {
                    msg.html html
                } else if (text != null) {
                    msg.text text
                } else {
                    throw new RuntimeException("Must provide 'html' or 'text' for email.")
                }

                for (Map f : attachmentsUse) {
                    String fileName = f.fileName as String,
                        contentType = f.contentType as String
                    Object src = f.contentSource
                    if (src instanceof byte[]) {
                        msg.attach fileName, contentType, (byte[]) src
                    } else if (src instanceof File) {
                        msg.attach fileName, contentType, (File) src
                    } else if (src instanceof InputStreamSource) {
                        msg.attach fileName, contentType, (InputStreamSource) src
                    } else {
                        throw new RuntimeException(
                            "Attachment '$fileName' must provide a contentSource of byte[], File, or InputStreamSource."
                        )
                    }
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
        return parseAddresses(configService.getStringList(configName).join(','))
    }

    /**
     *  Parse a comma delimited list of email addresses into a list of trimmed, properly
     *  formatted addresses, appending the xhEmailDefaultDomain config to any unqualified address.
     *
     *  Blank/whitespace-only entries are discarded - an empty or blank input yields an empty list,
     *  never a bare "@domain" address.
     *
     *  Includes special support for returning null if given {@link AppConfig#NONE}, to allow for
     *  sourcing optional / potentially-empty addresses from string configs.
     */
    List<String> parseAddresses(String s) {
        return s?.trim()?.equalsIgnoreCase(AppConfig.NONE) ? null : formatAddresses(s)
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
        filter ? (List<String>) filter.intersect(rawEmails) : rawEmails.toList()
    }

    /**
     * Normalize a String (comma-delimited) or Collection of addresses into a list of trimmed,
     * fully-qualified addresses. Blank entries are dropped, and the xhEmailDefaultDomain config is
     * appended to any unqualified address (skipped if that config is itself unset or "none").
     */
    private List<String> formatAddresses(Object o) {
        if (!o) return []

        List<String> raw = o instanceof CharSequence ?
            o.toString().tokenize(',') :
            (o as Collection<?>).collect { it?.toString() }

        List<String> ret = raw.collect { it?.trim() }.findAll { it } as List<String>
        if (!ret) return []

        String defaultDomain = configService.getString('xhEmailDefaultDomain')?.trim()
        if (!defaultDomain || defaultDomain.equalsIgnoreCase(AppConfig.NONE)) return ret
        if (!defaultDomain.startsWith('@')) defaultDomain = '@' + defaultDomain

        return ret.collect { it.contains('@') ? it : it + defaultDomain }
    }

    private List<Map> parseAttachments(Object attachments) {
        if (!attachments) return []
        return attachments instanceof Map ? [attachments as Map] : (attachments as List<Map>)
    }

    private Map createLogMsg(
        String logIdentifier,
        String subject,
        Object to,
        String fromUse,
        List<String> filters,
        List<String> override
    ) {
        Map<String, Object> ret = [
            _id : (logIdentifier ?: subject ?: '[No Subject]').take(70),
            from: fromUse,
            to  : to?.toString()?.take(255)
        ] as Map<String, Object>

        if (override) {
            ret.redirectedTo = override
        } else if (filters) {
            ret.filtered = true
        }

        return ret
    }
}
