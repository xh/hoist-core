> **Status: DRAFT** — This document is awaiting review. Content may be incomplete or subject to
> change. Do not remove this banner until the document has been interactively reviewed and approved.

# Activity Tracking

## Overview

Hoist's activity tracking system logs user actions, performance timings, client errors, and
feedback within a running application. Track entries are persisted as `TrackLog` domain objects,
viewable in the Admin Console, and published as cluster-wide events for real-time monitoring.

The system serves several purposes:
- **Usage analytics** — Which features are used, how often, and by whom
- **Performance monitoring** — Elapsed time for key operations
- **Error capture** — Client-side errors reported automatically to the server
- **User feedback** — In-app feedback routed to email
- **Audit logging** — CRUD operations tracked via `RestController`

## Source Files

| File | Location | Role |
|------|----------|------|
| `TrackLog` | `grails-app/domain/io/xh/hoist/track/` | GORM domain — persisted track entries |
| `TrackService` | `grails-app/services/io/xh/hoist/track/` | Primary service — `track()` API, severity filtering |
| `TrackLoggingService` | `grails-app/services/io/xh/hoist/track/` | Log-file output for track entries |
| `TrackSeverity` | `src/main/groovy/io/xh/hoist/track/` | Severity enum — `DEBUG`, `INFO`, `WARN`, `ERROR` |
| `ClientErrorEmailService` | `grails-app/services/io/xh/hoist/track/` | Email notifications for client errors |
| `FeedbackEmailService` | `grails-app/services/io/xh/hoist/track/` | Email routing for user feedback |

## Key Classes

### TrackLog

A GORM domain class representing a single tracked event. Stored in the `xh_track_log` table.

| Property | Type | Description |
|----------|------|-------------|
| `username` | `String` | User who performed the action |
| `impersonating` | `String` | If impersonating, the impersonated username |
| `dateCreated` | `Date` | When the event occurred (set explicitly, not auto-timestamped) |
| `category` | `String` | Grouping category (e.g., `'Default'`, `'Client Error'`, `'Audit'`) |
| `msg` | `String` | Concise action description (max 255 chars) |
| `data` | `String` | Additional JSON payload (TEXT column) |
| `elapsed` | `Integer` | Duration in milliseconds |
| `severity` | `String` | `DEBUG`, `INFO`, `WARN`, or `ERROR` |
| `correlationId` | `String` | Links related entries across client/server |
| `loadId` | `String` | Client app load identifier |
| `tabId` | `String` | Browser tab identifier |
| `instance` | `String` | Server instance name |
| `clientAppCode` | `String` | Client application identifier |
| `browser` | `String` | Detected browser name/version |
| `device` | `String` | Detected device type |
| `userAgent` | `String` | Raw User-Agent header |
| `appVersion` | `String` | Application version |
| `appEnvironment` | `String` | Runtime environment |
| `url` | `String` | Client URL (max 500 chars) |

The `dateCreated` field is set explicitly by `TrackService` (not auto-timestamped by GORM) to
preserve the actual client-side timestamp of the event.

Database indices exist on `username`, `dateCreated`, `category`, `tabId`, and `clientAppCode` for
efficient querying.

#### Helper Methods

- `getDataAsObject()` — Parse the `data` JSON string to a `Map`
- `getErrorSummary()` — Extract a short error message from client error data
- `getIsClientError()` — `true` if `category == 'Client Error'`

### TrackService

The primary service for recording track entries. Provides the `track()` method used by both
server-side code and client-side submissions.

#### `track()`

The main API for recording a single track entry:

```groovy
trackService.track(
    msg: 'Loaded portfolio',
    category: 'Portfolio',
    elapsed: 1250,
    data: [fundId: 'FUND-001', positionCount: 42]
)
```

Parameters:

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `msg` | `String` | (required) | Concise description of the action |
| `category` | `String` | `'Default'` | Grouping category |
| `severity` | `Object` | `INFO` | `TrackSeverity` or string |
| `data` | `Object` | `null` | Additional payload (serialized to JSON) |
| `logData` | `Object` | `null` | Keys from data to include in log output |
| `correlationId` | `String` | `null` | Links related entries |
| `timestamp` | `Long` | current time | Epoch ms when the event occurred |
| `elapsed` | `Long` | `null` | Duration in ms |
| `username` | `String` | `authUsername` (authenticated user) | Override for async contexts. Note: this is the *authenticated* user, not the apparent user. During impersonation, `authUsername` is the real user, while the impersonated user is captured in `impersonating`. |
| `impersonating` | `String` | auto-detected | The impersonated (apparent) user, if impersonation is active. Override for async contexts |

The method is intentionally fire-and-forget — it processes entries asynchronously on a background
thread to avoid delaying the calling request.

#### `trackAll(entries)`

Record multiple track entries in a single call. Used by the client-side tracking endpoint to batch
multiple events.

#### Processing Pipeline

When `track()` is called:

1. **Check enabled** — If tracking is disabled via config, return immediately.
2. **Rate limiting** — Monitor entry rate; disable persistence if rate exceeds `maxEntriesPerMin`.
3. **Prepare entry** — Enrich with request context (username, browser, instance, etc.).
4. **Background processing** (async):
   a. Create a `TimestampedLogEntry` and write to the application log via `TrackLoggingService`.
   b. Create a `TrackLog` domain object.
   c. If persistence is enabled and severity passes the filter, save to the database.
   d. Publish to the `xhTrackReceived` cluster topic.

#### Severity Filtering

The `xhActivityTrackingConfig` config's `levels` property controls which entries are persisted.
Entries are matched against rules in order — the first matching rule determines the minimum
severity:

```json
{
  "enabled": true,
  "maxDataLength": 2000,
  "maxEntriesPerMin": 1000,
  "levels": [
    {"username": "noisy.user", "category": "*", "severity": "WARN"},
    {"username": "*", "category": "Client Error", "severity": "DEBUG"},
    {"username": "*", "category": "*", "severity": "INFO"}
  ]
}
```

In this example:
- Entries from `noisy.user` are only persisted at `WARN` or above.
- `Client Error` entries are always persisted (any severity).
- All other entries need at least `INFO` severity.

#### Rate Limiting

`TrackService` monitors the rate of incoming entries. If the rate exceeds `maxEntriesPerMin`
(configurable, default 1000), persistence is temporarily disabled to protect the database. Logging
and topic publishing continue. Persistence re-enables automatically after two consecutive
compliant periods.

#### Cluster Event

Every tracked entry (even those not persisted) is published to the `xhTrackReceived` Hazelcast
topic. Services can subscribe to this for real-time monitoring:

```groovy
void init() {
    subscribeToTopic(
        topic: 'xhTrackReceived',
        onMessage: { TrackLog tl ->
            if (tl.isClientError) notifyOpsTeam(tl)
        }
    )
}
```

### TrackSeverity

An enum with four levels: `DEBUG`, `INFO`, `WARN`, `ERROR`. The `parse()` method converts strings
to severity values (defaulting to `INFO` for unrecognized input).

### Client Error and Feedback Emails

Hoist provides two email services that integrate with the tracking system:

#### ClientErrorEmailService

Sends email notifications when client-side errors are tracked. Uses a timer (`processErrors`) that
periodically queries the database for recent `TrackLog` entries with `category == 'Client Error'`.
The timer interval is driven by the `intervalMins` setting in `xhClientErrorConfig`. Multiple
errors discovered in a single interval are summarized into a digest-style email. Email recipients
and formatting are controlled via soft configuration.

#### FeedbackEmailService

Routes user feedback submitted through the hoist-react feedback dialog to email recipients. The
feedback is also tracked as a `TrackLog` entry with category `'Feedback'`.

## Configuration

| Config | Type | Description |
|--------|------|-------------|
| `xhActivityTrackingConfig` | `json` | Main tracking config (see below) |
| `xhClientErrorConfig` | `json` | Controls the `ClientErrorEmailService` timer and email behavior (see below) |

### `xhActivityTrackingConfig` Structure

```json
{
  "clientHealthReport": {"intervalMins": -1},
  "enabled": true,
  "levels": [
    {"username": "*", "category": "*", "severity": "INFO"}
  ],
  "logData": false,
  "maxDataLength": 2000,
  "maxEntriesPerMin": 1000,
  "maxRows": {"default": 10000, "limit": 25000, "options": [1000, 5000, 10000, 25000]}
}
```

| Key | Description |
|-----|-------------|
| `clientHealthReport` | Config for client health report submissions. `intervalMins` controls frequency (`-1` to disable) |
| `enabled` | `true` to enable tracking, `false` to disable completely |
| `levels` | Severity filtering rules (see Severity Filtering above) |
| `logData` | Default for whether to include data keys in log output |
| `maxDataLength` | Maximum size of JSON data payload (chars). Larger data is dropped |
| `maxEntriesPerMin` | Rate limit threshold for persistence |
| `maxRows` | Controls the maximum number of rows returned in admin activity queries. `default` is the initial row count, `limit` is the absolute maximum, and `options` provides selectable values |

### `xhClientErrorConfig` Structure

```json
{
  "intervalMins": 2
}
```

| Key | Description |
|-----|-------------|
| `intervalMins` | Interval in minutes at which `ClientErrorEmailService` queries for recent client errors and sends digest emails. Set to `-1` to disable error emails |

### Instance Config

| Config | Description |
|--------|-------------|
| `disableTrackLog` | Set to `'true'` to disable persistence only (logging and UI still active) |

This is useful for local development where you don't need track entries polluting the database.

## Common Patterns

### Server-side Action Tracking

Track significant operations with timing:

```groovy
class PortfolioService extends BaseService {

    Map loadPortfolio(String fundId) {
        long start = System.currentTimeMillis()
        def result = fetchPortfolioData(fundId)

        trackService.track(
            msg: 'Loaded portfolio',
            category: 'Portfolio',
            elapsed: System.currentTimeMillis() - start,
            data: [fundId: fundId, positionCount: result.positions.size()]
        )

        return result
    }
}
```

### Audit Tracking via RestController

`RestController` provides built-in audit tracking when `trackChanges = true`:

```groovy
class PositionController extends RestController {
    static restTarget = Position
    static trackChanges = true    // logs create/update/delete with 'Audit' category
}
```

### Monitoring for Client Errors

Subscribe to the tracking topic to react to client errors in real time:

```groovy
class ErrorMonitorService extends BaseService {

    void init() {
        subscribeToTopic(
            topic: 'xhTrackReceived',
            onMessage: { TrackLog tl ->
                if (tl.isClientError) {
                    logWarn("Client error from ${tl.username}: ${tl.errorSummary}")
                }
            },
            primaryOnly: true
        )
    }
}
```

## Client Integration

The hoist-react client tracks user activity through `XH.track()` and its tracking service. Client
entries are batched and sent to the server's `/xh/track` endpoint, where `TrackService.trackAll()`
processes them.

Client-side errors are automatically captured and sent as track entries with
`category: 'Client Error'`. The `data` payload includes the error message, stack trace, and
component information.

User feedback submitted through the built-in feedback dialog is sent as a track entry with
`category: 'Feedback'` and routed to email via `FeedbackEmailService`.

## Common Pitfalls

### Tracking too much data

Large `data` payloads are dropped if they exceed `maxDataLength` (default 2000 chars). Track only
the essential context needed for debugging — IDs, counts, and key parameters rather than full
response bodies.

### Tracking in hot loops

`track()` is fire-and-forget but still has overhead (JSON serialization, background thread, topic
publish). Don't call it inside tight loops or on every row of a large dataset. Track aggregate
operations instead.

### Not handling async contexts

When tracking from timers or background threads (outside a request context), the current user is
not available. Pass `username` explicitly:

```groovy
// ✅ Do: Provide username in async context
trackService.track(msg: 'Scheduled refresh completed', username: 'system')

// ❌ Don't: Rely on request context in a timer
trackService.track(msg: 'Scheduled refresh completed')  // username will be null
```

### Ignoring rate limiting

If you see "Track persistence disabled due to non-compliant load" in logs, the application is
generating track entries faster than `maxEntriesPerMin`. Either increase the limit (if the load is
legitimate) or reduce tracking frequency in the application code.
