> **Status: DRAFT** — This document is awaiting review. Content may be incomplete or subject to
> change. Do not remove this banner until the document has been interactively reviewed and approved.

# Email

## Overview

Hoist-core provides a centralized email sending system via `EmailService`, a wrapper around the
Grails Mail Plugin that adds config-driven safety controls for non-production environments. The
system solves three problems:

1. **Safe non-production emailing** — Configs (`xhEmailFilter`, `xhEmailOverride`) prevent
   accidental delivery to real users when testing in dev/staging environments.
2. **Automated operational notifications** — Client errors, user feedback, and status monitor alerts
   are automatically emailed to support/ops teams without any app-specific code.
3. **Consistent sender/domain defaults** — Unqualified usernames (e.g. `jsmith`) are automatically
   qualified with a configurable domain, and a default sender address is always available.

All email configs are `xh`-prefixed AppConfigs, managed at runtime via the Hoist Admin Console.
No code changes or redeployments are needed to adjust email routing, filtering, or recipients.

## Source Files

| File | Location | Role |
|------|----------|------|
| `EmailService` | `grails-app/services/io/xh/hoist/email/` | Core email sending with config-driven filtering, overrides, and address normalization |
| `ClientErrorEmailService` | `grails-app/services/io/xh/hoist/track/` | Timer-based digest emails for client-side errors reported via `TrackService` |
| `FeedbackEmailService` | `grails-app/services/io/xh/hoist/track/` | Event-driven emails for end-user feedback submitted from the client |
| `MonitorReportService` | `grails-app/services/io/xh/hoist/monitor/` | Emails status monitor alerts when monitors enter or exit failure/warning states |
| `BootStrap` | `grails-app/init/io/xh/hoist/` | Registers all required `xh`-prefixed email AppConfigs at startup |

## Key Classes

### EmailService

**Location:** `grails-app/services/io/xh/hoist/email/EmailService.groovy`

The central service for all outbound email. Application services and the built-in notification
services (client errors, feedback, monitors) all route through `EmailService.sendEmail()`.

#### `sendEmail(Map args)`

The primary method. Accepts a map of arguments:

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `to` | `String` or `List<String>` | Yes | — | Recipient address(es) |
| `from` | `String` | No | `xhEmailDefaultSender` config | Sender address |
| `cc` | `String` or `List<String>` | No | — | CC recipients |
| `bcc` | `String` or `List<String>` | No | — | BCC recipients |
| `subject` | `String` | No | `''` | Email subject (truncated to 255 chars) |
| `html` | `String` | One of `html`/`text` | — | HTML email body |
| `text` | `String` | One of `html`/`text` | — | Plain text email body |
| `attachments` | `Map` or `List<Map>` | No | — | File attachments (see below) |
| `markImportant` | `boolean` | No | `false` | Set high-priority email headers |
| `async` | `boolean` | No | `false` | Send asynchronously |
| `doLog` | `boolean` | No | `true` | Log send information |
| `logIdentifier` | `String` | No | `subject` | Custom identifier for log messages |
| `throwError` | `boolean` | No | `false` | Throw on error vs. suppress and log |

**Attachment maps** must contain:
- `fileName` (`String`) — the file name
- `contentType` (`String`) — MIME type
- `contentSource` (`byte[]`, `File`, or `InputStreamSource`) — the content

#### Processing Pipeline

When `sendEmail()` is called, the following steps occur in order:

1. **Address normalization** — All addresses are trimmed and unqualified names (those without `@`)
   get the `xhEmailDefaultDomain` config appended (e.g. `jsmith` becomes `jsmith@example.com`).

2. **Filter application** — If `xhEmailFilter` is set (not `"none"`), only addresses present in
   the filter list survive. Other recipients are silently dropped. This is applied to `to`, `cc`,
   and `bcc` independently via set intersection.

3. **Override application** — If `xhEmailOverride` is set (not `"none"`), the override address(es)
   replace all recipients. `cc` and `bcc` are cleared entirely. The original intended recipient(s)
   are noted in the subject line for traceability.

4. **Local development guard** — If running in local development mode (`Utils.isLocalDevelopment`)
   with no override *and* no filter configured, email is silently suppressed with a log message.
   This prevents developers from accidentally sending mail when configs have not been explicitly set.

5. **Subject enhancement** — In non-production environments, the environment name is appended to the
   subject in brackets (e.g. `[STAGING]`). When an override is active, the original recipient info
   is also appended (e.g. `[STAGING, for 3 recipients]` or `[STAGING, for jsmith@example.com]`).

6. **Send** — The email is dispatched via the Grails Mail Plugin's `sendMail` closure.

7. **Stats tracking** — `emailsSent` count and `lastSentDate` are updated for admin monitoring.

#### `parseMailConfig(String configName)`

Reads an `xh`-prefixed AppConfig containing comma-separated email addresses and returns a
normalized `List<String>`. Used by both `EmailService` itself and the notification services
(`ClientErrorEmailService`, `FeedbackEmailService`, `MonitorReportService`) to read their recipient
lists from config.

#### `parseAddresses(String s)`

Parses a comma-delimited string of email addresses into a normalized list. Returns `null` for the
special string `"none"`, which allows configs to explicitly disable email by setting their value to
`none`.

#### Admin Stats

`EmailService.getAdminStats()` reports the current values of all four email configs plus
`emailsSent` count and `lastSentDate`, viewable in the Hoist Admin Console.

### ClientErrorEmailService

**Location:** `grails-app/services/io/xh/hoist/track/ClientErrorEmailService.groovy`

Sends digest-style email alerts when client-side JavaScript errors are reported to `TrackService`.
Rather than emailing per-error (which could flood inboxes during an incident), this service batches
errors using a configurable timer:

- **Timer interval** — Controlled by `xhClientErrorConfig.intervalMins` (default: 2 minutes).
- **Recipients** — Read from the `xhEmailSupport` config via `emailService.parseMailConfig()`.
- **Primary-only** — The timer runs only on the primary cluster instance (`primaryOnly: true`).
- **Digest format** — A single error produces a detailed email with metadata (user, browser,
  version, URL, stack trace). Multiple errors are combined into a digest with a summary per error.
- **Query window** — Each timer run queries `TrackLog` entries with category `'Client Error'`
  created since the last successful run.

### FeedbackEmailService

**Location:** `grails-app/services/io/xh/hoist/track/FeedbackEmailService.groovy`

Sends emails immediately when end users submit feedback from the client-side feedback dialog
(provided by hoist-react).

- **Event-driven** — Subscribes to the `xhTrackReceived` cluster topic and filters for entries
  with category `'Feedback'`. This means feedback emails are triggered in near-real-time rather
  than on a timer.
- **Recipients** — Read from the `xhEmailSupport` config.
- **Primary-only** — The subscription uses `primaryOnly: true`.
- **Format** — Includes the user's feedback message plus metadata (username, app, version,
  environment, browser, device, timestamp).

### MonitorReportService

**Location:** `grails-app/services/io/xh/hoist/monitor/MonitorReportService.groovy`

Emails status monitor reports when monitors transition into or out of failure/warning states.

- **Recipients** — Read from the `xhMonitorEmailRecipients` config (separate from `xhEmailSupport`
  since monitoring alerts often go to a different ops team).
- **Thresholds** — Controlled by `xhMonitorConfig.failNotifyThreshold` and
  `warnNotifyThreshold` — the number of consecutive check cycles a monitor must be in the
  respective status before triggering an alert.
- **Repeat interval** — While in alert mode, reports are re-sent every
  `xhMonitorConfig.monitorRepeatNotifyMins` minutes (default: 60).
- **Pub/sub** — In addition to emailing, reports are published on the `xhMonitorStatusReport`
  topic for app-specific alerting integrations.

## Configuration

All email-related AppConfigs are registered in `BootStrap.ensureRequiredConfigsCreated()` and are
prefixed with `xh`. They can be edited at runtime via the Hoist Admin Console.

| Config | Type | Default | Client Visible | Description |
|--------|------|---------|----------------|-------------|
| `xhEmailDefaultDomain` | `string` | `example.com` | No | Domain appended to unqualified usernames (e.g. `jsmith` becomes `jsmith@example.com`). Leading `@` is optional — the service normalizes it. |
| `xhEmailDefaultSender` | `string` | `support@example.com` | No | Default `from` address used when `sendEmail()` is called without an explicit `from` argument. |
| `xhEmailFilter` | `string` | `none` | No | Comma-separated whitelist of addresses that may receive email. Only addresses present in this list will be delivered to. Value `"none"` disables filtering (all addresses pass through). |
| `xhEmailOverride` | `string` | `none` | No | Address(es) to which **all** email is redirected, regardless of specified recipients. CC/BCC are cleared. The original recipients are noted in the subject. Value `"none"` disables the override. |
| `xhEmailSupport` | `string` | `none` | Yes | Address(es) for support/feedback email delivery. Used by `ClientErrorEmailService` and `FeedbackEmailService`. Value `"none"` disables these notifications. |
| `xhMonitorEmailRecipients` | `string` | `none` | No | Address(es) for status monitor alert emails. Used by `MonitorReportService`. Value `"none"` disables monitor email alerts. |
| `xhClientErrorConfig` | `json` | `{"intervalMins": 2}` | No | Controls client error email batching. `intervalMins` sets the timer interval for `ClientErrorEmailService`. |
| `xhMonitorConfig` | `json` | `{"monitorRefreshMins": 10, ...}` | No | Controls monitor alerting thresholds and repeat intervals. See Monitoring docs for full details. |

### Recommended Non-Production Setup

For staging or QA environments, configure **one** of the following safety mechanisms:

- **`xhEmailOverride`** — Set to a team distribution list or test mailbox. All email is redirected
  there regardless of intended recipients. Best for testing the full email flow end-to-end.
- **`xhEmailFilter`** — Set to a comma-separated list of allowed addresses. Only matching
  recipients receive email; others are silently dropped. Best when only specific testers should
  receive mail.

If **neither** is set and the app is running in local development mode, `EmailService` suppresses
all email automatically as a safety net.

## Common Patterns

### Sending a Simple Email from Application Code

```groovy
class MyAppService extends BaseService {

    EmailService emailService

    void notifyUserOfCompletion(String username, String reportName) {
        emailService.sendEmail(
            to: username,               // Will be qualified with xhEmailDefaultDomain
            subject: "Report Ready: ${reportName}",
            html: "<p>Your report <b>${reportName}</b> is ready for download.</p>"
        )
    }
}
```

The `to` value `username` (e.g. `jsmith`) is automatically expanded to `jsmith@example.com` using
the `xhEmailDefaultDomain` config. No need to manually construct full addresses.

### Sending Email with Attachments

```groovy
emailService.sendEmail(
    to: ['analyst-team'],
    subject: 'Daily Summary Report',
    html: '<p>Please find the daily summary attached.</p>',
    attachments: [
        [
            fileName: 'summary.xlsx',
            contentType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
            contentSource: excelFileBytes
        ]
    ]
)
```

### Sending to Multiple Recipients with CC

```groovy
emailService.sendEmail(
    to: ['manager@example.com', 'team-lead@example.com'],
    cc: 'audit-log@example.com',
    subject: 'Trade Approval Required',
    html: buildApprovalHtml(trade),
    markImportant: true
)
```

### Reading Config-Driven Recipients

```groovy
// In your custom service — read a list of addresses from an AppConfig,
// with automatic normalization and "none" handling
List<String> recipients = emailService.parseMailConfig('myAppAlertRecipients')
if (recipients) {
    emailService.sendEmail(to: recipients, subject: 'Alert', text: 'Something happened.')
}
```

### Async Sending for Non-Blocking Operations

```groovy
// Use async: true when sending from request-handling code to avoid
// blocking the HTTP response on SMTP delivery
emailService.sendEmail(
    to: 'ops-team',
    subject: 'Batch Job Complete',
    text: "Processed ${count} records.",
    async: true
)
```

### Error Handling — Throw vs. Suppress

```groovy
// Default: errors are logged but suppressed (throwError defaults to false).
// The calling code continues executing.
emailService.sendEmail(to: 'user', subject: 'FYI', text: 'Info')

// Explicit throw: use when email delivery is critical to the operation
try {
    emailService.sendEmail(
        to: 'compliance@example.com',
        subject: 'Regulatory Filing Submitted',
        html: filingHtml,
        throwError: true
    )
} catch (Exception e) {
    // Handle delivery failure — e.g. retry, alert, or fail the operation
    throw new RuntimeException("Failed to send compliance notification", e)
}
```

## Client Integration

### Client Error Emails

The flow from a JavaScript error to an email notification:

1. **hoist-react** catches an unhandled exception and posts it to the server's `TrackService`
   endpoint with category `'Client Error'`.
2. **`TrackService`** persists a `TrackLog` record and publishes the entry on the
   `xhTrackReceived` cluster topic.
3. **`ClientErrorEmailService`** runs on a timer (every `xhClientErrorConfig.intervalMins` minutes,
   primary instance only). On each tick, it queries for `TrackLog` entries with category
   `'Client Error'` created since the last run.
4. If errors are found, they are formatted into a **single digest email** — one error gets a
   detailed report with stack trace; multiple errors get a condensed summary per error separated
   by horizontal rules.
5. The email is sent asynchronously to the `xhEmailSupport` recipients.

### Feedback Emails

The flow from a user's feedback submission to an email notification:

1. The user fills out the **feedback dialog** in hoist-react and submits.
2. **hoist-react** posts the feedback to `TrackService` with category `'Feedback'`.
3. **`TrackService`** persists a `TrackLog` record and publishes the entry on the
   `xhTrackReceived` cluster topic.
4. **`FeedbackEmailService`** receives the topic message (subscribed with `primaryOnly: true`),
   checks if the category is `'Feedback'`, and sends an email **immediately** (no batching).
5. The email includes the user's message and metadata (username, version, browser, device,
   timestamp) and is sent asynchronously to the `xhEmailSupport` recipients.

### Monitor Alert Emails

1. **`MonitorService`** runs status checks on a timer and publishes results.
2. **`MonitorReportService`** evaluates whether monitors have crossed failure/warning thresholds.
3. If a state change is detected (or a repeat interval has elapsed while still in alert mode), a
   `MonitorStatusReport` is generated and emailed to `xhMonitorEmailRecipients`.

## Common Pitfalls

### Forgetting to Configure Email Configs Before Going Live

All email configs default to placeholder values (`example.com`, `support@example.com`, `none`).
If you deploy to production without updating these, emails will either be silently dropped (because
`xhEmailSupport` is `"none"`) or sent to/from invalid addresses.

```
// xhEmailDefaultDomain
example.com          // <-- default placeholder, will not deliver

// xhEmailSupport
none                 // <-- disables client error and feedback emails entirely
```

Before your first production deployment, configure at minimum:
- `xhEmailDefaultDomain` — your organization's actual email domain
- `xhEmailDefaultSender` — a real sender address (e.g. `noreply@yourcompany.com`)
- `xhEmailSupport` — your support team's address

### Relying on Filter Instead of Override for Dev Testing

```groovy
// xhEmailFilter = "dev-tester@example.com"
```

Using `xhEmailFilter` alone in development means emails to addresses **not** in the filter list are
silently dropped. This can lead to confusion — "why didn't I get the email?" — when the test
recipient was simply not in the filter.

```groovy
// xhEmailOverride = "dev-tester@example.com"
```

Using `xhEmailOverride` is usually better for development because **all** email is redirected to
your test address. The subject line includes the original recipient, so you can verify targeting
without any risk of delivery to real users.

### Not Providing `html` or `text` Body

```groovy
// Bad -- will throw RuntimeException
emailService.sendEmail(to: 'user@example.com', subject: 'Hello')   // No body!
```

`sendEmail()` requires exactly one of `html` or `text`. Omitting both throws:
`RuntimeException("Must provide 'html' or 'text' for email.")`

```groovy
// Good
emailService.sendEmail(to: 'user@example.com', subject: 'Hello', text: 'World')
```

### Passing Full Addresses When Usernames Suffice

```groovy
// Unnecessary -- manually appending domain that xhEmailDefaultDomain handles
emailService.sendEmail(
    to: "${username}@mycompany.com",
    subject: 'Update',
    text: 'Done.'
)
```

```groovy
// Better -- let EmailService qualify the address via xhEmailDefaultDomain config
emailService.sendEmail(
    to: username,
    subject: 'Update',
    text: 'Done.'
)
```

Letting `EmailService` handle domain qualification keeps your code decoupled from the email domain
and allows it to be changed via config without code updates.

### Sending Synchronously in Request Handlers

```groovy
// Blocks the HTTP response until SMTP completes -- slow for the user
def someControllerAction() {
    emailService.sendEmail(to: 'ops', subject: 'Event', text: 'Something happened.')
    renderJSON(success: true)
}
```

```groovy
// Better -- send async so the response is not blocked by SMTP latency
def someControllerAction() {
    emailService.sendEmail(to: 'ops', subject: 'Event', text: 'Something happened.', async: true)
    renderJSON(success: true)
}
```

Use `async: true` whenever the caller does not need to know whether delivery succeeded. All
built-in notification services (`ClientErrorEmailService`, `FeedbackEmailService`,
`MonitorReportService`) send asynchronously.

### Assuming Errors Will Propagate

By default, `sendEmail()` catches all exceptions, logs them, and continues silently
(`throwError` defaults to `false`). If email delivery is critical to your workflow, you must
explicitly opt in:

```groovy
// Default behavior -- error is swallowed, caller never knows
emailService.sendEmail(to: 'critical@example.com', subject: 'Important', text: 'Must deliver')
// Code continues even if email failed!

// Explicit error propagation when delivery matters
emailService.sendEmail(
    to: 'critical@example.com',
    subject: 'Important',
    text: 'Must deliver',
    throwError: true
)
```
