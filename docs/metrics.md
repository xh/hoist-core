# Observable Metrics

## Overview

Hoist-core provides a central metrics infrastructure built on [Micrometer](https://micrometer.io/),
enabling applications to publish observable metrics to platforms such as Prometheus, Grafana, and
Datadog. The system is designed to work transparently across Hoist's clustered architecture, with
automatic namespace prefixing, default tags, and cluster-wide scrape support.

The framework automatically publishes a range of built-in metrics covering JVM health, JDBC
connection pooling, WebSocket activity, client activity tracking, and Hoist monitor results.
Applications can register their own custom metrics using the standard Micrometer API via
`MetricsService.registry`.

### Key capabilities

- **Central registry** — `MetricsService` exposes a `CompositeMeterRegistry` that all meters
  register through.
- **Export registries** — built-in support for Prometheus (pull-based) and OTLP (push-based),
  configured via soft config. Additional registries (e.g. Datadog) can be added programmatically.
- **Opt-in publishing** — metrics are exported only once their names are published, managed by
  admins from the Admin Console's Servers > Metrics tab.
- **Client metrics** — browser-side timers and counters are relayed to the server registry, so
  client and server metrics share one naming scheme and one set of backends.
- **Cluster-wide Prometheus scrape** — a single endpoint can return metrics from all instances,
  each distinguished by a `xh.instance` tag.
- **Built-in metrics** — JVM (memory, GC, threads, classloader, CPU), JDBC pool, WebSocket
  channels, client activity tracking, and Hoist monitor results are instrumented out of the box.
- **Admin Console** — a cluster-wide metrics viewer is available via `MetricsAdminController`.

---

## Source Files

| File | Location | Role |
|------|----------|------|
| `MetricsService.groovy` | `grails-app/services/io/xh/hoist/telemetry/metric/` | Central Micrometer registry, namespace/tagging, export registries |
| `MetricsConfig.groovy` | `src/main/groovy/io/xh/hoist/telemetry/metric/` | Typed wrapper around `xhMetricsConfig` |
| `BuiltInMetricsService.groovy` | `grails-app/services/io/xh/hoist/telemetry/metric/impl/` | Internal service binding the standard JVM/system/Logback/Tomcat meters and the JDBC connection-pool meters |
| `MonitorMetricsService.groovy` | `grails-app/services/io/xh/hoist/monitor/` | Publishes Hoist monitor results as Micrometer metrics |
| `TrackMetricsService.groovy` | `grails-app/services/io/xh/hoist/track/` | Client activity metrics from track log entries |
| `MetricsAdminService.groovy` | `grails-app/services/io/xh/hoist/admin/` | Cluster-wide meter listing for admin UI |
| `MetricsAdminController.groovy` | `grails-app/controllers/io/xh/hoist/admin/cluster/` | REST endpoint for admin metrics viewer |

---

## MetricsService

**File:** `grails-app/services/io/xh/hoist/telemetry/metric/MetricsService.groovy`

The central service for all Micrometer metrics in a Hoist application. Initialized in the ordered
early phase of bootstrap — after `ConfigService`, `TraceService`, `LogLevelService`, and
`ClusterService`, but before the parallel initialization of all remaining services, so those can
register meters in their own `init()`. Provides the `CompositeMeterRegistry` that all framework and
application meters register through.

### Registry and meter registration

Access the registry via `metricsService.registry` — a standard Micrometer
`CompositeMeterRegistry` that supports all Micrometer meter builders directly. For the common
cases, `MetricsService` exposes a family of methods that handle registration, default tags,
distribution config, and name-prefixing from an optional `owner` BaseService's `telemetryPrefix`:

| Method | Use for |
|---|---|
| `configureTimer(name, description?, tags?, percentiles?, slos?, publishHistogram?, minExpected?, maxExpected?, owner?, useNamePrefix?)` | Configures distribution stats and default metadata for a named Timer (no concrete Timer registered). |
| `registerTimer(name, description?, tags?, owner?, useNamePrefix?)` | Registers a concrete Timer (uses distribution config from any prior `configureTimer`). |
| `configureCounter(name, description?, tags?, owner?, useNamePrefix?)` | Configures default metadata for a named Counter. |
| `registerCounter(name, description?, tags?, owner?, useNamePrefix?)` | Registers a concrete Counter for the name. |
| `registerGauge(name, valueFn, description?, tags?, baseUnit?, owner?, useNamePrefix?)` | Registers a Gauge whose value is read from `valueFn` on demand. |
| `registerFunctionCounter(name, countFn, description?, tags?, baseUnit?, owner?, useNamePrefix?)` | Registers a monotonically-increasing FunctionCounter from `countFn`. |
| `recordTimer(name, valueMs, tags?, owner?, useNamePrefix?)` | Records a single timing against the named Timer, registering it on first use. |
| `recordCount(name, value?, tags?, owner?, useNamePrefix?)` | Increments the named Counter, registering it on first use. |

`recordTimer` and `recordCount` are the fire-and-forget forms — no need to hold a meter reference.
These are what [`ObservedRun`](./tracing.md#observedrun) calls for its `.timer()` / `.counter()`
builder methods.

Additional methods support export and administration rather than instrumentation:

| Method | Use for |
|---|---|
| `addPublishRegistry(reg)` / `removePublishRegistry(reg)` | Add or remove an export sink — see [Adding custom registries](#adding-custom-registries). |
| `getPublishedMetrics()` | Names currently allowed to reach export sinks (from `xhMetricsPublished`). |
| `updatePublishedMetrics(names, published)` | Add or remove names from that list, persisting to `xhMetricsPublished`. |
| `prometheusData()` | Cluster-wide Prometheus exposition output. |
| `submitClientMetrics(entries)` | Relay browser-side metrics — see [Client Metrics](#client-metrics). |
| `getMeterDescription(name)` | Registered description for a metric name. |
| `readOnlyRegistry` | In-memory registry for *reading* current values on this instance. Not for registering meters. |

Pass `owner: this` from your service to have its `telemetryPrefix` prepended to the metric name
and an `xh.owner` tag added automatically. Set `useNamePrefix: false` to opt out of prefixing
when supplying a fully-qualified name.

```groovy
class MyService extends BaseService {

    String telemetryPrefix = 'myService'
    MetricsService metricsService

    void init() {
        metricsService.registerGauge(
            name: 'queueDepth',
            description: 'Current items in processing queue',
            valueFn: { queueSize() },
            owner: this
        )
    }
}
```

For meter shapes the above methods don't cover (e.g. `DistributionSummary`), use the underlying
`metricsService.registry` directly with the Micrometer builder API — remember to prefix the
metric name yourself, e.g. `"${telemetryPrefix}.myMeter"`.

### Default tags

All meters registered through the service automatically receive:

1. **Default tags:**
   - `xh.application` — the application code (e.g. `myApp`)
   - `xh.instance` — the cluster instance name (e.g. `e36ca82b`)
   - `xh.source` — classifies the metric's origin ('hoist' or 'app')

### Cluster-scoped metrics

Metrics tagged with `xh.instance: 'cluster'` are only accepted on the primary instance. This
prevents duplicate registration of cluster-level aggregates (such as overall monitor status) across
multiple instances.

---

## Export Registries

> **Enabling a registry is not enough — metrics must also be published.** Every export sink has a
> filter applied that denies any meter whose name is not listed in the `xhMetricsPublished` config.
> That list defaults to **empty**, so a freshly configured app with `prometheusEnabled: true`
> exports *nothing* until metrics are published. See
> [Publishing metrics](#publishing-metrics).

### Prometheus

When `prometheusEnabled: true` in `xhMetricsConfig`, a `PrometheusMeterRegistry` is added to the
composite registry. Prometheus scrapes are served by calling `metricsService.prometheusData()`, which
fans out to all cluster instances via Hazelcast, collects each instance's scrape output, and
concatenates the results. Each metric already carries a `xh.instance` tag distinguishing its source.

Applications expose this via a simple controller:

```groovy
import io.xh.hoist.BaseController
import io.xh.hoist.security.AccessAll

@AccessAll
class PrometheusController extends BaseController {

    def metricsService

    def index() {
        render(
            contentType: 'text/plain; version=0.0.4; charset=utf-8',
            text: metricsService.prometheusData()
        )
    }
}
```

This cluster-wide endpoint should be used instead of the spring default, `/actuator/prometheus`
which will not contain any Hoist metrics and is not configured by default.

Additional Prometheus configuration properties can be passed via the `prometheusConfig` map in
`xhMetricsConfig`. Keys are mapped to Micrometer's `PrometheusConfig` properties (e.g.
`{"step": "PT30S"}`).

### OTLP

When `otlpEnabled: true`, an `OtlpMeterRegistry` is added for push-based export (e.g. to
Grafana Cloud, New Relic, or any OTLP-compatible backend). Configuration properties are passed via
`otlpConfig` (e.g. `{"url": "https://otlp.example.com/v1/metrics", "step": "PT60S"}`).

### Local-development gating

OTLP export is suppressed by default when the app is running in local development, even when
`otlpEnabled: true` in `xhMetricsConfig`. This avoids polluting a shared OTLP backend with
developer-machine metrics during routine work. The same gating applies to trace export — see
[`tracing.md`](./tracing.md#local-development-gating).

To opt in, set the `otlpEnabledInLocalDev` instance config to `'true'`. Local-development
detection follows `Utils.isLocalDevelopment`, which reflects the Grails runtime mode
(`Environment.isDevelopmentMode()` — true when started via `bootRun`, false in a deployed war).
This is independent of the configured `appEnvironment`, so a deployed instance configured as
`Development` is not affected by this flag.

When OTLP export runs in local dev, the `deployment.environment.name` resource attribute is
suffixed with the OS username (e.g. `Development-johndoe`) so per-developer data can be
distinguished in a shared backend. Override
[`ClusterConfig.getOtelResourceAttributes()`](https://github.com/xh/hoist-core/blob/develop/grails-app/init/io/xh/hoist/ClusterConfig.groovy)
if your backend prefers a different scheme.

### Adding custom registries

Applications can add additional export registries programmatically:

```groovy
metricsService.addPublishRegistry(myDatadogRegistry)
```

Use `addPublishRegistry()` rather than `registry.add()` — it attaches the publish filter to the new
registry, so the sink receives only published metrics. Adding to the composite registry directly
bypasses that filter and floods the sink with every meter. Pair with `removePublishRegistry()` to
detach.

---

## Built-in Metrics

### JVM, system, and container metrics

`BuiltInMetricsService` binds Micrometer's standard binders at startup — the same set Spring Boot
autoconfiguration would provide:

| Metric prefix | Binder | Description |
|---------------|--------|-------------|
| `jvm.memory.*` | `JvmMemoryMetrics` | Heap and non-heap memory usage |
| `jvm.gc.*` | `JvmGcMetrics` | Garbage collection counts and pause times |
| `jvm.threads.*` | `JvmThreadMetrics` | Thread counts by state |
| `jvm.classes.*` | `ClassLoaderMetrics` | Loaded and unloaded class counts |
| `jvm.compilation.*` | `JvmCompilationMetrics` | JIT compilation time |
| `jvm.memory.usage.after.gc`, `jvm.gc.overhead` | `JvmHeapPressureMetrics` | Heap pressure signals |
| `jvm.info` | `JvmInfoMetrics` | JVM version/vendor as a tagged meter |
| `system.cpu.*`, `process.cpu.*` | `ProcessorMetrics` | CPU usage and available processors |
| `process.files.*` | `FileDescriptorMetrics` | Open vs. max file descriptors |
| `process.uptime`, `process.start.time` | `UptimeMetrics` | Process uptime |
| `logback.events` | `LogbackMetrics` | Log event counts by level |
| `tomcat.*` | `TomcatMetrics` | Servlet container sessions, threads, requests |

### JDBC connection pool metrics

Published by `BuiltInMetricsService`, reading the **primary** injected `DataSource`. Requires an
`org.apache.tomcat.jdbc.pool.DataSource` at the bottom of the proxy chain — if the primary pool is
some other implementation, the service logs a warning at startup and publishes no JDBC metrics.
Additional Grails datasources are not covered.

| Metric | Type | Description |
|--------|------|-------------|
| `jdbc.connections.active` | Gauge | Active/in-use connections |
| `jdbc.connections.idle` | Gauge | Idle connections |
| `jdbc.connections.max` | Gauge | Maximum pool size |
| `jdbc.connections.min` | Gauge | Minimum idle connections |
| `jdbc.connections.pending` | Gauge | Threads waiting for a connection |
| `jdbc.connections.borrowed` | FunctionCounter | Connections borrowed from pool |
| `jdbc.connections.returned` | FunctionCounter | Connections returned to pool |
| `jdbc.connections.created` | FunctionCounter | Connections created |
| `jdbc.connections.released` | FunctionCounter | Connections released/destroyed |
| `jdbc.connections.reconnected` | FunctionCounter | Connections re-established after failure |
| `jdbc.connections.abandoned` | FunctionCounter | Connections removed due to abandonment |
| `jdbc.connections.evicted` | FunctionCounter | Idle connections released by evictor |

The first four are the pool-agnostic names Spring Boot also publishes; the rest are Tomcat-pool
specifics. For richer pool diagnostics — including rolling historical snapshots — see the
Connection Pool tab in the Admin Console, backed by `ConnectionPoolMonitoringService`.

### WebSocket metrics

Published by `WebSocketService` under its `xh.websocket` `telemetryPrefix`:

| Metric | Type | Description |
|--------|------|-------------|
| `xh.websocket.channels` | Gauge | Active WebSocket channels |
| `xh.websocket.messages.sent` | Counter | Messages sent successfully |
| `xh.websocket.messages.received` | Counter | Messages received from clients |
| `xh.websocket.messages.sendErrors` | Counter | Message send failures |
| `xh.websocket.sessions.opened` | Counter | Sessions registered |
| `xh.websocket.sessions.closed` | Counter | Sessions unregistered |

### Monitor metrics

Published by `MonitorMetricsService` after each monitor evaluation cycle on the primary instance,
under its `xh.monitor` `telemetryPrefix`. For each configured monitor, three metrics are published:

| Metric | Type | Description |
|--------|------|-------------|
| `xh.monitor.status.{code}` | Gauge | Status severity (0=INACTIVE .. 4=FAIL) |
| `xh.monitor.value.{code}` | Gauge | Current numeric metric value |
| `xh.monitor.executionTime.{code}` | Timer | Execution time of the monitor check |

Each carries a `xh.instance` tag indicating which cluster instance ran the check, or `cluster` for
aggregate status. Meters are automatically removed when monitors or instances are decommissioned.

See [`monitoring.md`](./monitoring.md) for full documentation of the Hoist monitoring system.

### Client activity metrics

Published by `TrackMetricsService`, which subscribes to the `xhTrackReceived` Hazelcast topic on
the primary instance. These metrics are cluster-scoped (`xh.instance: 'cluster'`) and tagged with
`xh.clientApp` to distinguish activity from different client applications.

| Metric | Type | Description |
|--------|------|-------------|
| `xh.client.track.messages` | Counter | All track log entries received |
| `xh.client.track.errors` | Counter | Client error track entries (`category == 'Client Error'`) |
| `xh.client.load.totalTime` | Timer | Total app load elapsed time |
| `xh.client.load.authTime` | Timer | App load authentication phase duration |

Load timers are recorded only for `App` / `Loaded` track entries that include a `timings` map in
their data payload, confirming they represent a standard Hoist client load event. Both timers
emit percentile histograms, supporting server-side aggregation (e.g. p90/p99) in Prometheus and
OTLP-receiving backends.

See [`activity-tracking.md`](./activity-tracking.md) for documentation of the track log system.

---

## Configuration

### `xhMetricsConfig`

| Property | Value |
|----------|-------|
| **Type** | `json` |
| **Default** | See below |
| **Client Visible** | No |
| **Purpose** | Metrics infrastructure configuration — export registries and namespace. |

**Default value:**

```json
{
    "prometheusEnabled": false,
    "otlpEnabled": false,
    "prometheusConfig": {},
    "otlpConfig": {}
}
```

| Key | Type | Description |
|-----|------|-------------|
| `prometheusEnabled` | Boolean | Enable the Prometheus export registry. Dynamic — takes effect on next config refresh. |
| `prometheusConfig` | Map | Additional Prometheus configuration properties (e.g. `{"step": "PT30S"}`). |
| `otlpEnabled` | Boolean | Enable the OTLP export registry. Dynamic. In local development, additionally gated — see [Local-development gating](#local-development-gating). |
| `otlpConfig` | Map | OTLP configuration properties (e.g. `{"url": "...", "step": "PT60S"}`). |

When `xhMetricsConfig` is updated, the export registries are torn down and recreated with the
new settings. This is handled by `clearCaches()` responding to the `xhConfigChanged` event.

### Publishing metrics

| Property | Value |
|----------|-------|
| **Config** | `xhMetricsPublished` |
| **Type** | `json` |
| **Default** | `[]` — an empty list, meaning **nothing is exported** |
| **Client Visible** | No |
| **Purpose** | Allowlist of metric names to include in Prometheus, OTLP, or other exports. |

A publish filter is applied to every export registry, denying any meter whose name is not in this
list. Meters are always registered and visible locally regardless — this controls export only. Two
consequences worth internalizing:

- Enabling `prometheusEnabled` or `otlpEnabled` on its own exports nothing.
- Metrics are opted in **by name**, so a newly added meter will not appear in a backend until it is
  published, even if similar metrics already are.

**Admins manage this list through the Admin Console — Servers > Metrics.** That tab lists every
metric available across the cluster and provides the interface to publish and unpublish them,
writing the result back to `xhMetricsPublished`. This is the intended workflow for reviewing and
updating publishing status; edit the raw config directly only if the UI is unavailable. The tab is
backed by `MetricsAdminController.listMetrics` and `setPublished`, the latter requiring the
`HOIST_ADMIN` role.

Programmatic equivalents are available on the service — `getPublishedMetrics()` to read the current
list and `updatePublishedMetrics(names, published)` to modify it — for apps that need to seed a
baseline set at bootstrap.

---

## Client Metrics

Browser-side metrics are posted to the `xh/recordMetrics` endpoint and folded into the server's
registry, so client and server metrics land in the same backends under one naming scheme. This is
the metrics counterpart to the [client span relay](./tracing.md#client-span-relay).

`XhController.recordMetrics` hands the payload to `MetricsService.submitClientMetrics()`, which
expects a list of entries shaped as:

| Field | Description |
|-------|-------------|
| `name` | Metric name (required). Used verbatim — no prefix is applied. |
| `value` | Numeric value (required). Milliseconds for timers. |
| `type` | `'timer'` or `'count'`. |
| `tags` | Optional map of tag key/values. |

Each entry is routed to `recordTimer` or `recordCount`, registering the meter on first use. Entries
missing a `name` or `value`, or carrying an unrecognized `type`, are skipped with a warning rather
than failing the batch. As with any other meter, client metrics reach an export backend only once
their names are published — see
[Publishing metrics](#publishing-metrics).

---

## Admin Console

The **Servers > Metrics** tab is the primary operational view — it lists every metric available
across the cluster and lets admins publish or unpublish each one, which is how
[`xhMetricsPublished`](#publishing-metrics) is meant to be maintained.

`MetricsAdminController` backs it with two actions: `listMetrics`, which fans out to all cluster
instances and returns a merged list of all registered meters, and `setPublished`, which updates the
published list and requires the `HOIST_ADMIN` role.

Each `listMetrics` entry includes:

- `name` — the fully-qualified metric name (with namespace prefix)
- `type` — Micrometer meter type (GAUGE, COUNTER, TIMER, etc.)
- `value` — the current value (interpretation depends on type)
- `count`, `max` — for Timer/DistributionSummary types
- `description` — human-readable description
- `baseUnit` — unit of measurement
- `tags` — all tags including `xh.application`, `xh.instance`, `xh.source`
- `stats` — raw statistics map

`listMetrics` requires the `HOIST_ADMIN_READER` role.
