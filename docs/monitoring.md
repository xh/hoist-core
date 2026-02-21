> **Status: DRAFT** — This document is awaiting review by the Hoist core development team.
> Generated from source analysis of hoist-core. Please verify all details before relying on this
> document for production decisions.

# Application Health Monitoring

## Overview

Hoist-core ships a built-in, cluster-aware health monitoring system. Its purpose is to give
operations teams and developers real-time visibility into the health of a running Hoist application
— and to proactively alert when things go wrong.

The system works as follows:

1. **Monitor definitions** (database-backed `Monitor` domain objects) declare *what* to check and
   *how* to evaluate the results (metric type, thresholds).
2. **Monitor implementations** (methods on the app's `MonitorDefinitionService`) contain the actual
   check logic — query a database, measure heap usage, call an external service, etc.
3. A **timer on the primary instance** periodically fans out monitor runs across every cluster
   member, collects the per-instance results, aggregates them to a single worst-case status per
   monitor, and publishes the consolidated report.
4. A **reporting layer** detects sustained failures or warnings (avoiding flapping), emails
   designated recipients, and publishes a Hazelcast topic so apps can wire up custom alerting
   (PagerDuty, Slack, etc.).
5. The **hoist-react Admin Console** polls the server for monitor results and renders them in a
   dedicated Monitors tab, giving admins a live dashboard and the ability to force an immediate
   re-run.

### Why it exists

Without built-in monitoring, every Hoist application would need to independently solve health
checking, threshold evaluation, multi-instance aggregation, and alert routing. The framework
provides a standardised solution with sensible defaults (heap usage, DB connectivity, client errors,
load times, cluster health) while remaining fully extensible for application-specific checks.

---

## Source Files

| File | Location | Role |
|------|----------|------|
| `Monitor.groovy` | `grails-app/domain/io/xh/hoist/monitor/` | GORM domain class — persisted monitor definition with thresholds |
| `MonitorStatus.groovy` | `src/main/groovy/io/xh/hoist/monitor/` | Enum of possible statuses: `INACTIVE`, `UNKNOWN`, `OK`, `WARN`, `FAIL` |
| `MonitorConfig.groovy` | `src/main/groovy/io/xh/hoist/monitor/` | Typed wrapper around the `xhMonitorConfig` JSON config |
| `MonitorResult.groovy` | `src/main/groovy/io/xh/hoist/monitor/` | Single-instance result of one monitor run — passed into check methods |
| `AggregateMonitorResult.groovy` | `src/main/groovy/io/xh/hoist/monitor/` | Cross-instance aggregation with cycle-based history tracking |
| `MonitorStatusReport.groovy` | `src/main/groovy/io/xh/hoist/monitor/` | Roll-up report across all monitors — title, HTML body, overall status |
| `MonitorService.groovy` | `grails-app/services/io/xh/hoist/monitor/` | Primary-only orchestrator — timer, fan-out, aggregation, caching |
| `MonitorEvalService.groovy` | `grails-app/services/io/xh/hoist/monitor/` | Per-instance runner — parallel execution, timeout, threshold evaluation |
| `MonitorReportService.groovy` | `grails-app/services/io/xh/hoist/monitor/` | Alert logic — flap suppression, email, topic publication |
| `MonitorSpec.groovy` | `src/main/groovy/io/xh/hoist/monitor/` | Typed specification for required monitor definitions |
| `MonitorMetricType.groovy` | `src/main/groovy/io/xh/hoist/monitor/` | Enum of metric types: `Floor`, `Ceil`, `None` |
| `DefaultMonitorDefinitionService.groovy` | `src/main/groovy/io/xh/hoist/monitor/provided/` | Optional base class providing built-in monitor implementations |
| `MonitorResultsAdminController.groovy` | `grails-app/controllers/io/xh/hoist/admin/` | REST endpoints for the Admin Console Monitors tab |

---

## Key Classes

### `Monitor` (Domain)

**File:** `grails-app/domain/io/xh/hoist/monitor/Monitor.groovy`

The GORM domain class persisted to the `xh_monitor` database table. Each row defines a single
health check and its evaluation criteria. Monitors are managed through the Admin Console UI and
are editable at runtime without redeployment.

**Key fields:**

| Field | Type | Purpose |
|-------|------|---------|
| `code` | `String` | Unique identifier — must match the method name on `MonitorDefinitionService` |
| `name` | `String` | Human-readable display name |
| `metricType` | `MonitorMetricType` | One of `Floor`, `Ceil`, `None` — controls threshold comparison direction |
| `metricUnit` | `String` | Display label for the metric (e.g. `'ms'`, `'%'`, `'errors'`) |
| `warnThreshold` | `Integer` | Metric value that triggers `WARN` status |
| `failThreshold` | `Integer` | Metric value that triggers `FAIL` status |
| `params` | `String` | JSON string of arbitrary parameters passed into the check method |
| `active` | `boolean` | Whether this monitor should be evaluated (defaults to `false`) |
| `primaryOnly` | `boolean` | If `true`, only the primary cluster instance runs this check |
| `sortOrder` | `Integer` | Controls display ordering in the Admin Console |
| `notes` | `String` | Free-text documentation for admins |
| `lastUpdatedBy` | `String` | Username of last editor |
| `lastUpdated` | `Date` | Timestamp of last edit |

**Metric type semantics:**

- **`Ceil`** — the metric value should stay *below* the thresholds. A value *above* warn/fail
  triggers that status. Example: heap usage percentage, error count.
- **`Floor`** — the metric value should stay *above* the thresholds. A value *below* warn/fail
  triggers that status. Example: available disk space, cache hit rate.
- **`None`** — no automatic threshold evaluation. The check method itself must set the status
  directly. Useful for pass/fail checks that do not produce a numeric metric.

### `MonitorStatus` (Enum)

**File:** `src/main/groovy/io/xh/hoist/monitor/MonitorStatus.groovy`

Ordered enum whose `ordinal()` doubles as severity. This ordering is critical — `max()` operations
across results naturally produce the worst-case status.

```
INACTIVE(0) < UNKNOWN(1) < OK(2) < WARN(3) < FAIL(4)
```

- **`INACTIVE`** — monitor is disabled or the subsystem it checks is not enabled.
- **`UNKNOWN`** — initial state before first evaluation.
- **`OK`** — all checks passed and metric is within thresholds.
- **`WARN`** — metric crossed the warn threshold but not fail, or the check set it explicitly.
- **`FAIL`** — metric crossed the fail threshold, the check threw an exception, or the check
  timed out.

### `MonitorResult`

**File:** `src/main/groovy/io/xh/hoist/monitor/MonitorResult.groovy`

Represents the result of running a single monitor on a single cluster instance. An instance of this
class is created by `MonitorEvalService` and passed into the app's check method. The check method
populates it.

**Key properties and methods:**

```groovy
class MonitorResult {
    MonitorStatus status   // Defaults to UNKNOWN; set to OK automatically if not changed
    Object metric          // The numeric (or other) value produced by the check
    String message         // Human-readable detail — shown in Admin Console and alert emails

    Map getParams()                         // Parsed JSON params from the Monitor definition
    <T> T getParam(String name, T defaultVal = null)  // Get a single param with a default
    <T> T getRequiredParam(String name)     // Get a param or throw if missing
    void prependMessage(String prependStr)   // Prepend text to any existing message
}
```

The `params` accessor deserialises the `Monitor.params` JSON field, giving check implementations
a clean way to receive per-monitor configuration (lookback windows, query users, table names, etc.)
without requiring code changes.

### `AggregateMonitorResult`

**File:** `src/main/groovy/io/xh/hoist/monitor/AggregateMonitorResult.groovy`

Consolidates `MonitorResult` entries from multiple cluster instances into a single status per
monitor. The overall status is the *worst* (highest ordinal) across all instances.

Also maintains cycle-based history:

| Field | Purpose |
|-------|---------|
| `cyclesAsSuccess` | Consecutive check cycles in `OK` status |
| `cyclesAsWarn` | Consecutive check cycles in `WARN` status |
| `cyclesAsFail` | Consecutive check cycles in `FAIL` status |
| `lastStatusChanged` | Timestamp of the most recent status transition |

These counters are essential for the reporting layer's flap-suppression logic. A monitor must
remain in `WARN` for `warnNotifyThreshold` consecutive cycles (or `FAIL` for
`failNotifyThreshold` cycles) before an alert is generated.

**Cycle counter behaviour on status transitions:**

- Transition to `FAIL` resets `cyclesAsSuccess` to 0 and increments `cyclesAsFail`, but
  *preserves* `cyclesAsWarn` (entering FAIL does not clear WARN streaks).
- Transition to `WARN` resets both `cyclesAsSuccess` and `cyclesAsFail` to 0.
- Transition to `OK` resets both `cyclesAsFail` and `cyclesAsWarn` to 0.

### `MonitorService`

**File:** `grails-app/services/io/xh/hoist/monitor/MonitorService.groovy`

The central orchestrator. Runs only meaningful work on the **primary instance** (via
`primaryOnly: true` on its timer). Responsibilities:

1. **Timer-driven polling** — runs every `monitorRefreshMins` minutes (from `xhMonitorConfig`),
   with a startup delay of `monitorStartupDelayMins`. Disabled in development mode and when
   `xhEnableMonitoring` is `false`.
2. **Cluster-wide fan-out** — uses `ClusterUtils.runOnAllInstances()` to invoke
   `MonitorEvalService.runAllMonitors()` on every cluster member.
3. **Result aggregation** — groups per-instance results by monitor code, merges with previous
   `AggregateMonitorResult` entries to maintain cycle history, and stores in a
   replicated `CachedValue`.
4. **Report triggering** — passes the final sorted results to `MonitorReportService`.
5. **Force run** — `forceRun()` allows on-demand execution from the Admin Console, even when
   auto-run is disabled.

The replicated `CachedValue` means any instance can serve the results to the Admin Console without
requiring the request to hit the primary.

### `MonitorEvalService`

**File:** `grails-app/services/io/xh/hoist/monitor/MonitorEvalService.groovy`

Runs on **every instance** when instructed by the primary. Handles:

1. **Parallel execution** — each active monitor runs in its own `Promises.task {}`.
2. **Timeout enforcement** — each task is constrained by `monitorTimeoutSecs` (default 15s). A
   timeout produces a `FAIL` result with an explanatory message.
3. **Method dispatch** — looks up the monitor's `code` as a method name on
   `monitorDefinitionService` via Groovy meta-programming (`respondsTo`). If the method is missing,
   the monitor fails with a clear error message.
4. **Threshold evaluation** — after the check method returns, `evaluateThresholds()` compares the
   metric against the `Monitor`'s warn/fail thresholds using the `metricType` (Ceil/Floor/None).
5. **Logging** — optionally logs each result to the monitor log (controlled by
   `writeToMonitorLog` in `xhMonitorConfig`).

**Execution flow for a single monitor:**

```
MonitorEvalService.runMonitor(monitor, timeout)
  |
  +-- Create MonitorResult (status = UNKNOWN)
  +-- Look up method on monitorDefinitionService by monitor.code
  +-- Execute method in async task with timeout
  |     +-- Check method populates result.metric, optionally result.status/message
  +-- If status is still UNKNOWN, set to OK
  +-- If status is not INACTIVE or FAIL, run evaluateThresholds()
  |     +-- Compare metric against warn/fail thresholds using Ceil/Floor logic
  +-- On exception or timeout: set status = FAIL, capture exception summary
  +-- Record elapsed time
  +-- Return MonitorResult
```

### `MonitorReportService`

**File:** `grails-app/services/io/xh/hoist/monitor/MonitorReportService.groovy`

Handles alert generation with built-in flap suppression. Called by `MonitorService` after each
evaluation cycle on the primary.

**Alert mode logic:**

Alert mode activates when:
- Any monitor's `cyclesAsFail >= failNotifyThreshold`, OR
- Any monitor's `cyclesAsWarn >= warnNotifyThreshold`, OR
- Alert mode is already active and at least one monitor is still at `WARN` or above.

Alert mode deactivates when no monitors are at `WARN` or above.

**Notification triggers:**

A `MonitorStatusReport` is generated and published when:
1. Alert mode transitions (enters or exits), OR
2. Alert mode is still active and `monitorRepeatNotifyMins` has elapsed since the last
   notification.

**Publication channels:**

- **Hazelcast topic** `xhMonitorStatusReport` — any service can subscribe to this for custom
  integrations (Slack, PagerDuty, etc.).
- **Email** — sent to the address(es) in the `xhMonitorEmailRecipients` config, if configured.

### `MonitorStatusReport`

**File:** `src/main/groovy/io/xh/hoist/monitor/MonitorStatusReport.groovy`

A simple value object that rolls up all `AggregateMonitorResult` entries into a single overall
status and a human-readable title (e.g. `"MyApp: 1 Failures | 2 Warnings | 5 OK"`).

Provides `toHtml()` for email body generation, listing only monitors at `WARN` or above with
their message and minutes-in-status.

### `MonitorSpec` and `MonitorMetricType`

**Files:** `src/main/groovy/io/xh/hoist/monitor/MonitorSpec.groovy`,
`src/main/groovy/io/xh/hoist/monitor/MonitorMetricType.groovy`

`MonitorSpec` is a typed specification class for defining required monitors via
`ensureRequiredMonitorsCreated()`. Its fields mirror the seedable fields of the `Monitor` domain
class. `MonitorMetricType` is an enum of supported metric types: `Floor`, `Ceil`, `None`.

### `DefaultMonitorDefinitionService`

**File:** `src/main/groovy/io/xh/hoist/monitor/provided/DefaultMonitorDefinitionService.groovy`

An optional base class that applications can extend to get a set of built-in monitors out of the
box. Applications are not required to use this class — they can write a `MonitorDefinitionService`
from scratch — but extending it is the recommended approach.

**Built-in monitors provided:**

| Monitor Code | What It Checks | Metric |
|-------------|----------------|--------|
| `xhMemoryMonitor` | Heap usage over a configurable lookback window | `%` (avg or max) |
| `xhClientErrorsMonitor` | Count of client-reported errors (primaryOnly) | Error count |
| `xhLoadTimeMonitor` | Longest tracked event in lookback window (primaryOnly) | Seconds |
| `xhDbConnectionMonitor` | Time to execute a trivial SELECT on the primary DB | Milliseconds |
| `xhLdapServiceConnectionMonitor` | Time to look up a user via LDAP (if enabled) | Milliseconds |
| `xhClusterBreaksMonitor` | Count of distributed object discrepancies across instances | Break count |

The `ensureRequiredConfigAndMonitorsCreated()` method (called by `init()`) auto-creates both the
required `xhMonitorConfig` soft-config entry and missing `Monitor` rows at startup with sensible
defaults.

#### `ensureRequiredMonitorsCreated()`

Applications should call this public method to register their own required monitors alongside the
built-in ones. When a new monitor check method is added, it is strongly recommended to include a
corresponding `MonitorSpec` entry via this method. This ensures the `Monitor` database row is
created automatically on startup with sensible defaults — without it, the monitor will not exist
until an admin manually creates it through the Admin Console, and the check method will fail with
a "not implemented" error in the meantime.

The method accepts a `List<MonitorSpec>` and creates any `Monitor` rows that are not already
present in the database. Existing monitors are never modified — the spec only provides initial
defaults.

```groovy
void init() {
    super.init()
    ensureRequiredMonitorsCreated([
        new MonitorSpec(
            code: 'myCustomCheck',
            name: 'My Custom Check',
            metricType: Ceil,
            metricUnit: 'ms',
            warnThreshold: 500,
            failThreshold: 1000,
            active: true
        )
    ])
}
```

---

## Configuration

All monitoring-related configuration is stored as `AppConfig` entries (soft config) and can be
changed at runtime through the Admin Console without redeployment.

### `xhEnableMonitoring`

| Property | Value |
|----------|-------|
| **Type** | `bool` |
| **Default** | `true` |
| **Client Visible** | Yes |
| **Purpose** | Master switch for the monitoring system. When `false`, the `MonitorService` timer is disabled. Monitors can still be run on-demand via `forceRun()`. |

### `xhMonitorConfig`

| Property | Value |
|----------|-------|
| **Type** | `json` |
| **Default** | See below |
| **Client Visible** | No |
| **Purpose** | Core monitoring parameters — refresh interval, alert thresholds, timeouts. |

**Default value and field descriptions:**

```json
{
    "monitorRefreshMins": 10,
    "failNotifyThreshold": 2,
    "warnNotifyThreshold": 5,
    "monitorStartupDelayMins": 1,
    "monitorRepeatNotifyMins": 60,
    "monitorTimeoutSecs": 15,
    "writeToMonitorLog": true
}
```

| Key | Type | Description |
|-----|------|-------------|
| `monitorRefreshMins` | Integer | Minutes between automatic monitor evaluation cycles. |
| `monitorStartupDelayMins` | Integer | Minutes to wait after app startup before the first run. Prevents false alarms during warm-up. |
| `monitorTimeoutSecs` | Integer | Per-monitor execution timeout in seconds. Exceeding this produces a `FAIL`. |
| `failNotifyThreshold` | Integer | Number of consecutive `FAIL` cycles before an alert is generated. Prevents flapping. |
| `warnNotifyThreshold` | Integer | Number of consecutive `WARN` cycles before an alert is generated. |
| `monitorRepeatNotifyMins` | Integer | Minutes between repeated notifications while alert mode is active. |
| `writeToMonitorLog` | Boolean | Whether to write individual monitor results to the application log. |

### `xhMonitorEmailRecipients`

| Property | Value |
|----------|-------|
| **Type** | `string` |
| **Default** | `'none'` |
| **Purpose** | Comma-separated email addresses for alert delivery. Value `'none'` disables email alerts. |

---

## Application Implementation

### The `MonitorDefinitionService` Contract

Every Hoist application that uses monitoring must provide a bean named `monitorDefinitionService`.
This is the service that contains the actual check logic. The `MonitorEvalService` looks up
methods on this service by name, matching the `Monitor.code` of each active monitor definition.

There are three approaches, from simplest to most custom:

#### 1. Extend `DefaultMonitorDefinitionService` (Recommended)

```groovy
package com.mycompany.myapp

import io.xh.hoist.monitor.MonitorResult
import io.xh.hoist.monitor.MonitorSpec
import io.xh.hoist.monitor.provided.DefaultMonitorDefinitionService

import static io.xh.hoist.monitor.MonitorMetricType.*

class MonitorDefinitionService extends DefaultMonitorDefinitionService {

    void init() {
        super.init()  // Registers built-in monitors
        ensureRequiredMonitorsCreated([
            new MonitorSpec(
                code: 'orderIngestionMonitor',
                name: 'Order Ingestion Lag',
                metricType: Ceil,
                metricUnit: 'minutes',
                warnThreshold: 10,
                failThreshold: 30,
                active: true,
                params: '{"sourceSystem": "OMS"}',
                notes: 'Checks how far behind the order ingestion pipeline is.'
            )
        ])
    }

    def orderIngestionMonitor(MonitorResult result) {
        def source = result.getRequiredParam('sourceSystem')
        def lagMinutes = orderService.getIngestionLag(source)
        result.metric = lagMinutes
        result.message = "Last order received ${lagMinutes}m ago from ${source}"
    }
}
```

#### 2. Register `DefaultMonitorDefinitionService` directly (No custom monitors)

If you need only the built-in monitors and have no application-specific checks, register the
default implementation in `grails-app/conf/spring/resources.groovy`:

```groovy
import io.xh.hoist.monitor.provided.DefaultMonitorDefinitionService

beans = {
    monitorDefinitionService(DefaultMonitorDefinitionService)
}
```

#### 3. Write from scratch (Full control)

Build a service from `BaseService` with no dependency on the default. You are responsible for
creating all `Monitor` rows and implementing all check methods.

### Method Signature Convention

Each monitor check method must:

- Accept a single `MonitorResult` parameter.
- Have a name that **exactly matches** the `Monitor.code` column value.
- Populate `result.metric` with a numeric value (for `Ceil`/`Floor` types).
- Optionally set `result.status` directly (for `None` type or to short-circuit).
- Optionally set `result.message` for human-readable context.

If the method does not set `result.status`, the framework defaults it to `OK` and then evaluates
thresholds. If the method throws an exception, the framework catches it, sets `FAIL`, and records
the exception detail.

---

## Common Patterns

### Pattern 1: Metric-based monitor with parameterised lookback

The most common pattern. Compute a numeric value; let the framework evaluate thresholds.

```groovy
def recentOrderCountMonitor(MonitorResult result) {
    def lookbackMins = result.getRequiredParam('lookbackMinutes')
    def cutoff = new Date(System.currentTimeMillis() - lookbackMins * 60_000L)
    result.metric = Order.countByCreatedDateGreaterThan(cutoff)
    result.message = "${result.metric} orders in last ${lookbackMins}m"
}
```

Configure the `MonitorSpec` with `metricType: Floor`, `warnThreshold: 10`,
`failThreshold: 1`, and `params: '{"lookbackMinutes": 60}'`.

### Pattern 2: Pass/fail check (no numeric metric)

For checks where a numeric threshold is not meaningful — use `metricType: None` and set the
status directly.

```groovy
def externalApiHealthMonitor(MonitorResult result) {
    def response = httpClient.get('https://api.partner.com/health')
    if (response.statusCode != 200) {
        result.status = MonitorStatus.FAIL
        result.message = "API returned HTTP ${response.statusCode}"
    }
    // If we reach here without setting status, framework defaults to OK
}
```

### Pattern 3: Conditionally inactive monitor

If the subsystem being checked is disabled, mark the monitor as `INACTIVE` and return early.
This prevents false alerts for features that are intentionally off.

```groovy
def cacheWarmingMonitor(MonitorResult result) {
    if (!cacheService.warmingEnabled) {
        result.status = MonitorStatus.INACTIVE
        return
    }
    result.metric = cacheService.getStaleEntryCount()
}
```

### Pattern 4: Using params for environment-specific configuration

The JSON `params` field on `Monitor` allows ops teams to tune check behaviour per environment
without code changes.

```groovy
def queueDepthMonitor(MonitorResult result) {
    def queueName = result.getRequiredParam('queueName')
    def depth = messagingService.getQueueDepth(queueName)
    result.metric = depth
    result.message = "Queue '${queueName}' has ${depth} pending messages"
}
```

The `params` in the database might be `{"queueName": "orders-prod"}` in production and
`{"queueName": "orders-staging"}` in staging, with different thresholds per environment.

### Pattern 5: Subscribing to the monitor report topic

Wire up custom alerting by subscribing to the Hazelcast topic published by `MonitorReportService`.

```groovy
class SlackAlertService extends BaseService {

    void init() {
        subscribeToTopic('xhMonitorStatusReport', this.&onMonitorReport)
    }

    private void onMonitorReport(MonitorStatusReport report) {
        if (report.status >= MonitorStatus.WARN) {
            slackClient.postMessage(
                channel: '#ops-alerts',
                text: report.title + '\n' + report.toHtml()
            )
        }
    }
}
```

---

## Client Integration

The monitoring system integrates with the **hoist-react Admin Console** through a dedicated
Monitors tab. The server-side endpoint is provided by `MonitorResultsAdminController`.

### Endpoints

| Endpoint | Method | Role Required | Description |
|----------|--------|---------------|-------------|
| `/monitorResultsAdmin/results` | GET | `HOIST_ADMIN_READER` | Returns current `AggregateMonitorResult` list as JSON |
| `/monitorResultsAdmin/forceRunAllMonitors` | POST | `HOIST_ADMIN` | Triggers an immediate monitor run on the primary |

### Admin Console Features

- **Live dashboard** — displays all monitors with their current status, metric value, unit,
  elapsed time, and per-instance breakdown.
- **Force run** — allows admins to trigger an immediate evaluation cycle without waiting for the
  next timer tick. Useful for verifying fixes or during incident response.
- **Monitor management** — `Monitor` domain objects are editable through the Admin Console,
  allowing admins to adjust thresholds, toggle `active`, change `params`, and add/remove monitors
  without redeployment.

### Data Flow: Server to Client

```
MonitorService (primary)
  --> CachedValue (replicated across cluster)
    --> MonitorResultsAdminController (any instance)
      --> hoist-react Admin Console (polls periodically)
```

Because results are stored in a replicated `CachedValue`, the Admin Console request can be served
by any cluster instance. The client does not need to know which instance is the primary.

---

## Common Pitfalls

### Do not name the method differently from the Monitor code

The `MonitorEvalService` dispatches to `monitorDefinitionService` using the `Monitor.code` as the
method name. A mismatch means the monitor will always fail with:

> "Monitor 'someCode' not implemented by this application's MonitorDefinitionService."

- &#x2705; `Monitor.code = 'orderLagMonitor'` and method is `def orderLagMonitor(MonitorResult result)`
- &#x274C; `Monitor.code = 'orderLagMonitor'` and method is `def checkOrderLag(MonitorResult result)`

### Do not forget to call `super.init()` when extending `DefaultMonitorDefinitionService`

If you override `init()` without calling `super.init()`, the built-in monitors will not be
registered. (The `xhMonitorConfig` soft-config entry is also created by `BootStrap`, so it will
still exist, but none of the default `Monitor` rows will be created.)

- &#x2705; Correct:
```groovy
void init() {
    super.init()
    // App-specific setup...
}
```

- &#x274C; Incorrect:
```groovy
void init() {
    // Missing super.init() — built-in monitors are silently not registered
    ensureRequiredMonitorsCreated([...])
}
```

### Do not return a non-numeric metric with `Ceil` or `Floor` metric type

If `metricType` is `Ceil` or `Floor`, the framework expects `result.metric` to be a `Number`.
A non-numeric value will produce a `FAIL` with the message:

> "Monitor failed to compute numerical metric."

**Avoid:** returning a String or null as the metric when the Monitor is configured as `Ceil` or
`Floor`. Use `metricType: None` if the check does not produce a numeric value.

### Do not perform long-running operations without considering the timeout

Each monitor runs within a timeout (default 15 seconds, configurable via
`xhMonitorConfig.monitorTimeoutSecs`). If your check involves a slow external call:

- Set an appropriate timeout on your HTTP client or query that is shorter than the monitor timeout.
- Consider caching results and checking freshness rather than making live calls on every cycle.
- If the check legitimately needs more time, increase `monitorTimeoutSecs` — but be aware this
  delays the entire evaluation cycle since all monitors run in parallel within the same window.

### Do not set `primaryOnly: true` unless truly necessary

Monitors with `primaryOnly: true` run only on the primary cluster instance. This is correct for
checks that query shared resources where running on every instance would produce duplicate or
misleading results (e.g. counting rows in a shared database table). However, for checks that
measure instance-local health (heap usage, thread pool saturation), leave `primaryOnly: false` so
every instance is independently checked.

- &#x2705; `primaryOnly: true` for `xhClientErrorsMonitor` (queries shared `TrackLog` table)
- &#x2705; `primaryOnly: false` for `xhMemoryMonitor` (measures local JVM heap)
- &#x274C; `primaryOnly: true` for a monitor that checks local thread pool utilization

### Do not assume monitors run in development mode

In development mode (`grails.util.Environment.isDevelopmentMode()`), the `MonitorService` timer
is disabled. This is intentional — it avoids noisy logs and unnecessary resource usage during
development. If you need to test monitors locally, use `MonitorService.forceRun()` or trigger
the check via the Admin Console's force-run button.
