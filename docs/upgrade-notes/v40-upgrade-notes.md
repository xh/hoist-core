# Hoist Core v40 Upgrade Notes

> **From:** v39.x → v40.0.1 | **Released:** 2026-05-14 | **Difficulty:** 🟢 LOW

## Overview

Hoist Core v40 brings a Grails 7.0 → 7.1 platform bump alongside a substantial cleanup of the
Micrometer metrics layer. The standout server-side change is a new structured meter-registration
API on `MetricsService` (`configureTimer` / `registerTimer`, etc.) that centralizes default tags,
distribution config, and descriptions per metric name. Headline framework changes:

- **`ObservedRun` metrics API simplification** — the pre-built-`Timer` / `Counter` variants are
  gone; callers register meters by name (with optional tags) and let `MetricsService` own the
  underlying meter lifecycle. Closures now also auto-emit an `xh.outcome` tag indicating
  success/failure.
- **Span and metric name prefixing via `BaseService.telemetryPrefix`** — services can declare a
  prefix once and drop it from their per-call span/timer/counter names. Adopt this opportunistically;
  no migration is required if you don't use spans or `ObservedRun`-driven metrics.
- **All Hoist built-in metric names renamed `hoist.*` → `xh.*`** — affects dashboards, Prometheus
  queries, and alerts that filter on the old names. The Hoist code emitting them changes
  automatically.
- **Grails 7.0 → 7.1, Hazelcast 5.6 → 5.7, OpenTelemetry BOM 1.61 → 1.62** — the OTel version is
  now resolved from `gradle.properties`, so apps must add an `opentelemetry.version` property.

There are no database schema changes in this release.

## Prerequisites

Before starting, ensure:

- [ ] Running hoist-core v39.x (no special intermediate version needed)
- [ ] **hoist-react pairing (informational only)** — v40 imposes no new minimum on hoist-react.
      If you want to opt into the new client-side metrics ingestion (`/xh/recordMetrics`), pair
      with `@xh/hoist >= 86.0`. Otherwise hoist-react v85.x is fine.

## Upgrade Steps

### 1. Update `gradle.properties`

Bump the hoist-core version and the platform versions, and add the new `opentelemetry.version`
property. The OpenTelemetry BOM version used to be pinned inside `hoist-core` via `build.gradle`
extra properties; it is now sourced from `gradle.properties` so apps can override.

**File:** `gradle.properties`

Before:
```properties
hoistCoreVersion=39.0.0
grailsVersion=7.0.9
hazelcast.version=5.6.0
```

After:
```properties
hoistCoreVersion=40.0.1
grailsVersion=7.1.1
hazelcast.version=5.7.0
opentelemetry.version=1.62.0
```

Then run `./gradlew assemble` (or your app's equivalent) to pull the new dependencies.

### 2. Migrate `ObservedRun.timer` / `.counter` to by-name form

The pre-built-instance variants `ObservedRun.timer(Timer)` and `ObservedRun.counter(Counter)`
were removed. Pass a metric name (and optional tags) instead — `MetricsService` will auto-create
the underlying meter on first record and merge in any defaults you configured via
`configureTimer` / `configureCounter`.

**Find affected call sites:**
```bash
grep -rE "\.(timer|counter)\(\s*[A-Za-z_][A-Za-z0-9_]*\s*\)" grails-app/ src/
```

Look for `.timer(...)` / `.counter(...)` calls passing a *variable* (a pre-built `Timer` /
`Counter`) rather than a string literal — those are the ones that need updating. Calls that
already pass a name string continue to work as-is.

Before:
```groovy
private Timer requestTimer = Timer.builder('myService.requests')
    .description('Requests handled')
    .tags('endpoint', 'foo')
    .register(metricsService.registry)

observe()
    .span(name: 'handleRequest')
    .timer(requestTimer)
    .run { /* ... */ }
```

After:
```groovy
void init() {
    // Optional: declare defaults centrally for the metric name
    metricsService.configureTimer(
        name: 'myService.requests',
        description: 'Requests handled',
        tags: [endpoint: 'foo']
    )
}

observe()
    .span(name: 'handleRequest')
    .timer(name: 'myService.requests')
    .run { /* ... */ }
```

Notes:

- The closure result is unchanged; the timer / counter is recorded in a `finally` block, so it
  always fires even if the closure throws.
- Each recorded variant now automatically receives an `xh.outcome` tag with value `success` or
  `failure` based on whether the closure threw. If you previously sliced timings or counts by
  success rate by hand, this is no longer required.

### 3. Adjust `span` call sites for the new signature

`ObservedRun.span` and `BaseService.span` now take their parameters in a different order
(`name`, `tags`, `kind`) and accept a new optional `useNamePrefix` flag. Most call sites use
named args and continue to work unchanged. The two patterns that need attention:

- **Bare-string positional `span('name')`** — still works, unchanged.
- **Positional `span('name', SpanKind.CLIENT, [tag: 'val'])`** — no longer compiles because
  the second positional slot is now `tags`, not `kind`. Switch to named args.

Before:
```groovy
observe().span('outboundCall', SpanKind.CLIENT, [endpoint: url])
```

After:
```groovy
observe().span(name: 'outboundCall', kind: SpanKind.CLIENT, tags: [endpoint: url])
```

### 4. (Optional) Adopt `telemetryPrefix` and the new `MetricsService` API

> **Strongly recommended for services emitting multiple spans or meters.** Skip if your service
> emits no spans or `ObservedRun`-driven metrics.

`BaseService` now exposes a `telemetryPrefix` property. When set, it is automatically prepended
(with a `.` separator) to span and metric names emitted via `observe().span(...)`,
`.timer(...)`, `.counter(...)`, `BaseService.span(...)`, and the `MetricsService.configure*` /
`register*` methods (when the service is passed as `owner`). This lets you declare the prefix
once at the service level and drop the boilerplate at each call site.

Before — every span/meter name carries the full prefix:
```groovy
class WeatherService extends BaseService {

    void getCurrent(String city) {
        span('toolbox.weather.getCurrent')
            .logDebug("Loading forecast for $city")
            .run { /* ... */ }
    }

    void getForecast(String city) {
        span('toolbox.weather.getForecast')
            .logDebug("Loading forecast for $city")
            .run { /* ... */ }
    }
}
```

After — declare the prefix once; use bare names at the call site:
```groovy
class WeatherService extends BaseService {

    String telemetryPrefix = 'toolbox.weather'

    void getCurrent(String city) {
        span('getCurrent')
            .logDebug("Loading forecast for $city")
            .run { /* ... */ }
    }

    void getForecast(String city) {
        span('getForecast')
            .logDebug("Loading forecast for $city")
            .run { /* ... */ }
    }
}
```

The same applies to direct `MetricsService` registrations — pass `owner: this` and the service's
`telemetryPrefix` is applied (and an `xh.owner` tag is added for attribution). See Toolbox's
`PortfolioService`, `GitHubService`, and `WeatherService` for canonical examples.

### 5. Update dashboards and queries for the `hoist.*` → `xh.*` metric rename

All Hoist built-in metrics that were previously emitted under the `hoist.*` prefix are now
emitted under `xh.*`. This is a behavior change with **no app-code action** — the framework
emits the new names automatically — but app dashboards, Prometheus / OTLP queries, and alerts
that filter on the old names need to be updated.

| Old name (v39) | New name (v40) |
|---|---|
| `hoist.client.track.messages` | `xh.client.track.messages` |
| `hoist.client.track.errors` | `xh.client.track.errors` |
| `hoist.client.load.totalTime` | `xh.client.load.totalTime` |
| `hoist.client.load.authTime` | `xh.client.load.authTime` |
| `hoist.monitor.status.{code}` | `xh.monitor.status.{code}` |
| `hoist.monitor.value.{code}` | `xh.monitor.value.{code}` |
| `hoist.monitor.executionTime.{code}` | `xh.monitor.executionTime.{code}` |
| `hoist.websocket.channels` | `xh.websocket.channels` |
| `hoist.websocket.messages.sent` | `xh.websocket.messages.sent` |
| `hoist.websocket.messages.received` | `xh.websocket.messages.received` |
| `hoist.websocket.messages.sendErrors` | `xh.websocket.messages.sendErrors` |
| `hoist.websocket.sessions.opened` | `xh.websocket.sessions.opened` |
| `hoist.websocket.sessions.closed` | `xh.websocket.sessions.closed` |

**Find affected dashboards / config:** search your Grafana JSON exports, Prometheus rule files,
alert definitions, and any app code that constructs PromQL or OTLP queries.

```bash
grep -rl "hoist\.\(client\|monitor\|websocket\)" .
```

### 6. (Optional) Wire up client-side metrics ingestion

v40 adds a `/xh/recordMetrics` endpoint that accepts a batch of client-emitted timer / counter
samples and forwards them to `MetricsService`. To opt in, upgrade the client to `@xh/hoist >=
86.0` and configure the corresponding client-side metric emitters per the hoist-react v86 docs.
No server-side action is required — the endpoint is wired up automatically by hoist-core.

### 7. Recompile and verify

```bash
./gradlew compileGroovy
```

The most likely compile failures are:

- A pre-built `Timer` / `Counter` still being passed to `ObservedRun.timer` / `.counter` —
  apply the rewrite from step 2.
- A 3-positional `span('name', kind, tags)` call — apply the rewrite from step 3.

## Verification Checklist

After completing all steps:

- [ ] `./gradlew compileGroovy` succeeds
- [ ] Application starts without errors
- [ ] Admin Console loads and is functional
- [ ] Admin Console > Cluster > Metrics shows built-in meters under the new `xh.*` names
- [ ] If JDBC tracing is enabled (`xhJdbcTraceConfig.enabled = true`): `xh.jdbc.connections.*`
      gauges appear in metrics and the Admin Console connection-pool view shows live snapshots
- [ ] Authentication works (login/logout), including LDAP if applicable
- [ ] If you use distributed tracing: spans still flow to your collector with the expected
      names (note any `telemetryPrefix` you adopted)
- [ ] App dashboards, Prometheus rules, and alerts updated for `hoist.*` → `xh.*`
- [ ] No deprecated `ObservedRun.timer(Timer)` / `.counter(Counter)` usages remain:
      `grep -rE "\.(timer|counter)\(\s*[A-Za-z_][A-Za-z0-9_]*\s*\)" grails-app/ src/`

## Reference

- [Grails 7.1 release notes](https://grails.org/blog/2024-grails-7.1.html)
- [Metrics guide](../metrics.md) — `MetricsService`, registration API, publishing sinks
- [Tracing guide](../tracing.md) — spans, OTLP export, sampling
- [Toolbox on GitHub](https://github.com/xh/toolbox) — canonical example of a Hoist app, now
  adopting `telemetryPrefix` and the by-name metrics API
