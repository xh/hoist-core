/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.test.fakes

import groovy.transform.CompileStatic
import io.xh.hoist.email.EmailService

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Recording {@link EmailService} for tests. Calls to `sendEmail()` are collected in
 * {@link #getSent} rather than delivered, so a spec can assert on what would have gone out:
 *
 * <pre>
 * service.notifyOwner(widget)
 *
 * expect:
 * testEmailService.lastSent.to == 'owner@example.com'
 * testEmailService.lastSent.subject.contains(widget.name)
 * </pre>
 *
 * Each recorded email is the Map of named arguments as passed, with null values and default
 * flags removed. Recipients are recorded as given - the `xhEmailOverride` and `xhEmailFilter`
 * configs are not applied. Registered by {@link io.xh.hoist.test.HoistUnitTest} as the
 * application's `emailService` bean and cleared before each feature.
 */
@CompileStatic
class TestEmailService extends EmailService {

    private final List<Map> emails = new CopyOnWriteArrayList<Map>()

    /** All emails sent so far, oldest first. */
    List<Map> getSent() {
        emails.asImmutable()
    }

    /** The most recently sent email, or null if none. */
    Map getLastSent() {
        emails ? emails.last() : null
    }

    /** Forget all sent emails. */
    void clear() {
        emails.clear()
    }

    @Override
    void sendEmail(
        Object to, Object cc, Object bcc, String from,
        String subject, String html, String text, Object attachments,
        boolean markImportant, boolean async, boolean doLog, String logIdentifier, boolean throwError
    ) {
        Map email = [
            to           : to,
            cc           : cc,
            bcc          : bcc,
            from         : from,
            subject      : subject,
            html         : html,
            text         : text,
            attachments  : attachments,
            markImportant: markImportant ?: null,
            async        : async ?: null,
            doLog        : doLog ? null : false,
            logIdentifier: logIdentifier,
            throwError   : throwError ?: null
        ].findAll { k, v -> v != null }
        emails << email
    }
}
