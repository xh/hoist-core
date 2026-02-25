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
  register through. Default tags (`application`, `instance`, `source`) and namespace prefixing are
  applied automatically.
- **Export registries** — built-in support for Prometheus (pull-based) and OTLP (push-based),
  configured via soft config. Additional registries (e.g. Datadog) can be added programmatically.
- **Cluster-wide Prometheus scrape** — a single endpoint can return metrics from all instances,
  each distinguished by an `instance` tag.
- **Built-in metrics** — JVM (memory, GC, threads, classloader, CPU), JDBC pool, WebSocket
  channels, client activity tracking, and Hoist monitor results are instrumented out of the box.
- **Admin Console** — a cluster-wide metrics viewer is available via `MetricsAdminController`.

---

## Source Files

| File | Location | Role |
|------|----------|------|
| `MetricsService.groovy` | `grails-app/services/io/xh/hoist/telemetry/` | Central Micrometer registry, namespace/tagging, export registries |
| `MetricsConfig.groovy` | `src/main/groovy/io/xh/hoist/telemetry/` | Typed wrapper around `xhMetricsConfig` |
| `MonitorMetricsService.groovy` | `grails-app/services/io/xh/hoist/monitor/` | Publishes Hoist monitor results as Micrometer metrics |
| `TrackMetricsService.groovy` | `grails-app/services/io/xh/hoist/track/` | Client activity metrics from track log entries |
| `MetricsAdminService.groovy` | `grails-app/services/io/xh/hoist/admin/` | Cluster-wide meter listing for admin UI |
| `MetricsAdminController.groovy` | `grails-app/controllers/io/xh/hoist/admin/cluster/` | REST endpoint for admin metrics viewer |

---

## MetricsService

**File:** `grails-app/services/io/xh/hoist/telemetry/MetricsService.groovy`

The central service for all Micrometer metrics in a Hoist application. Initialized early in the
bootstrap sequence (before other services), it provides the `CompositeMeterRegistry` that all
framework and application meters register through.

### Registry and meter registration

Access the registry via `metricsService.registry`. This is a standard Micrometer
`CompositeMeterRegistry` — all Micrometer meter builders (Gauge, Counter, Timer, etc.) work as
documented in the [Micrometer docs](https://micrometer.io/docs).

```groovy
import io.micrometer.core.instrument.Gauge

class MyService extends BaseService {

    MetricsService metricsService

    void init() {
        Gauge.builder('myService.queueDepth', this, { queueSize() as double })
            .description('Current items in processing queue')
            .baseUnit('items')
            .register(metricsService.registry)
    }
}
```

### Namespace prefixing and default tags

All meters registered through the service automatically receive:

1. **Default tags:**
   - `application` — the application code (e.g. `myApp`)
   - `instance` — the cluster instance name (e.g. `inst1`)
   - `source` — classifies the metric's origin (see below)

2. **Namespace prefix** based on the `source` tag:
   - `source=app` (default) — metric name is prefixed with the application namespace
     (e.g. `myApp.myService.queueDepth`)
   - `source=hoist` — prefixed with `hoist.` (e.g. `hoist.monitor.xhMemoryMonitor.status`)
   - `source=infra` — no prefix added (e.g. `jvm.memory.used`, `jdbc.pool.active`)

The namespace defaults to the application code and can be overridden via the `namespace` key in
`xhMetricsConfig`. Note that the namespace is applied at service initialization — a restart is
required to change it.

### Cluster-scoped metrics

Metrics tagged with `instance=cluster` are only accepted on the primary instance. This prevents
duplicate registration of cluster-level aggregates (such as overall monitor status) across multiple
instances.

---

## Export Registries

### Prometheus

When `prometheusEnabled: true` in `xhMetricsConfig`, a `PrometheusMeterRegistry` is added to the
composite registry. Prometheus scrapes are served by calling `metricsService.prometheusData()`, which
fans out to all cluster instances via Hazelcast, collects each instance's scrape output, and
concatenates the results. Each metric already carries an `instance` tag distinguishing its source.

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

### Adding custom registries

Applications can add additional export registries programmatically:

```groovy
metricsService.registry.add(myDatadogRegistry)
```

---

## Built-in Metrics

### JVM metrics (`source=infra`)

Automatically bound at startup via Micrometer's standard binders:

| Metric prefix | Source | Description |
|---------------|--------|-------------|
| `jvm.memory.*` | `JvmMemoryMetrics` | Heap and non-heap memory usage |
| `jvm.gc.*` | `JvmGcMetrics` | Garbage collection counts and pause times |
| `jvm.threads.*` | `JvmThreadMetrics` | Thread counts by state |
| `jvm.classes.*` | `ClassLoaderMetrics` | Loaded and unloaded class counts |
| `system.cpu.*` | `ProcessorMetrics` | CPU usage and available processors |

### JDBC connection pool metrics (`source=infra`)

Published by `ConnectionPoolMonitoringService` via the Tomcat JDBC pool:

| Metric | Type | Description |
|--------|------|-------------|
| `jdbc.pool.size` | Gauge | Total connections (active + idle) |
| `jdbc.pool.active` | Gauge | Active/in-use connections |
| `jdbc.pool.idle` | Gauge | Idle connections |
| `jdbc.pool.waitCount` | Gauge | Threads waiting for a connection |
| `jdbc.pool.borrowed` | Counter | Cumulative connections borrowed |
| `jdbc.pool.returned` | Counter | Cumulative connections returned |
| `jdbc.pool.created` | Counter | Cumulative connections created |
| `jdbc.pool.released` | Counter | Cumulative connections destroyed |
| `jdbc.pool.reconnected` | Counter | Connections re-established after failure |
| `jdbc.pool.removeAbandoned` | Counter | Connections removed due to abandonment |
| `jdbc.pool.releasedIdle` | Counter | Idle connections released by evictor |

### WebSocket metrics (`source=infra`)

Published by `WebSocketService`:

| Metric | Type | Description |
|--------|------|-------------|
| `websocket.channels` | Gauge | Active WebSocket channels |
| `websocket.messages.sent` | Counter | Messages sent successfully |
| `websocket.messages.received` | Counter | Messages received from clients |
| `websocket.messages.sendErrors` | Counter | Message send failures |
| `websocket.sessions.opened` | Counter | Sessions registered |
| `websocket.sessions.closed` | Counter | Sessions unregistered |

### Monitor metrics (`source=hoist`)

Published by `MonitorMetricsService` after each monitor evaluation cycle on the primary instance.
For each configured monitor, three metrics are published:

| Metric | Type | Description |
|--------|------|-------------|
| `hoist.monitor.{code}.status` | Gauge | Status severity (0=INACTIVE .. 4=FAIL) |
| `hoist.monitor.{code}.value` | Gauge | Current numeric metric value |
| `hoist.monitor.{code}.executionTime` | Timer | Execution time of the monitor check |

Each carries an `instance` tag indicating which cluster instance ran the check, or `cluster` for
aggregate status. Meters are automatically removed when monitors or instances are decommissioned.

See [`monitoring.md`](./monitoring.md) for full documentation of the Hoist monitoring system.

### Client activity metrics (`source=hoist`)

Published by `TrackMetricsService`, which subscribes to the `xhTrackReceived` Hazelcast topic on
the primary instance. These metrics are cluster-scoped (`instance=cluster`) and tagged with
`clientApp` to distinguish activity from different client applications.

| Metric | Type | Description |
|--------|------|-------------|
| `hoist.client.track.messages` | Counter | All track log entries received |
| `hoist.client.track.errors` | Counter | Client error track entries (`category == 'Client Error'`) |
| `hoist.client.load.totalTime` | Timer | Total app load elapsed time |
| `hoist.client.load.authTime` | Timer | App load authentication phase duration |

Load timers are recorded only for `App` / `Loaded` track entries that include a `timings` map in
their data payload, confirming they represent a standard Hoist client load event.

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
| `namespace` | String | Metric name prefix for `source=app` metrics. Defaults to the application code. Requires restart to change. |
| `prometheusEnabled` | Boolean | Enable the Prometheus export registry. Dynamic — takes effect on next config refresh. |
| `prometheusConfig` | Map | Additional Prometheus configuration properties (e.g. `{"step": "PT30S"}`). |
| `otlpEnabled` | Boolean | Enable the OTLP export registry. Dynamic. |
| `otlpConfig` | Map | OTLP configuration properties (e.g. `{"url": "...", "step": "PT60S"}`). |

When `xhMetricsConfig` is updated, the export registries are torn down and recreated with the
new settings. This is handled by `clearCaches()` responding to the `xhConfigChanged` event.

---

## Admin Console

`MetricsAdminController` provides a `listMetrics` endpoint that fans out to all cluster instances
and returns a merged list of all registered meters. Each entry includes:

- `name` — the fully-qualified metric name (with namespace prefix)
- `type` — Micrometer meter type (GAUGE, COUNTER, TIMER, etc.)
- `value` — the current value (interpretation depends on type)
- `count`, `max` — for Timer/DistributionSummary types
- `description` — human-readable description
- `baseUnit` — unit of measurement
- `tags` — all tags including `application`, `instance`, `source`
- `stats` — raw statistics map

This endpoint requires the `HOIST_ADMIN_READER` role.
