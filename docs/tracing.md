# Distributed Tracing

## Overview

Hoist-core provides distributed tracing built on [OpenTelemetry](https://opentelemetry.io/), using
the OTel SDK directly for span processing and export. The system supports OTLP export and is
configured dynamically via soft config.

Tracing is **disabled by default** and has negligible overhead when disabled — all public methods
delegate to no-op implementations, so no null checks are needed in application code.

### Key capabilities

- **Central service** — `TraceService` manages the OpenTelemetry SDK lifecycle, exporter
  pipeline, and provides span creation APIs (`withSpan` and `createSpan`).
- **Combined observability** — `ObservedRun` is a composable builder that wraps a closure with
  any combination of tracing, logging, and metrics. Accessed via `BaseService.observe()`.
- **Automatic request spans** — `HoistFilter` creates SERVER spans for every request.
  `HoistFilter` extracts incoming W3C `traceparent` headers so request spans join existing traces.
- **Export** — OTLP (HTTP/protobuf) export configured via soft config. Applications can register
  additional exporters (e.g. Zipkin) via `addExporter()`.
- **W3C trace propagation** — incoming `traceparent` headers are honored; outbound HTTP calls
  via `JSONClient` and `BaseProxyService` inject trace context automatically.
- **Cluster context propagation** — `ClusterTask` captures and restores trace context across
  Hazelcast remote execution, maintaining parent-child span relationships.
- **Thread context propagation** — Grails `task {}` calls automatically carry the current trace
  context to worker threads via `ContextPropagatingPromiseFactory`.
- **Client span relay** — Browser-generated spans are submitted to `xh/submitSpans` and merged
  with concurrent server-side spans for the same trace, producing end-to-end client-to-server
  traces. Clients submit all spans (no client-side sampling); the server owns the keep/drop
  decision for the unified trace.
- **Tail-based sampling** — All spans for a locally-rooted trace are buffered in memory until
  the root ends (or the trace times out). If *any* span in the trace ended in error, every span
  is exported. Otherwise configurable `sampleRules` decide at the trace level (matching against
  the root span's name and tags) with a single per-trace probability roll.
- **Log correlation** — `traceId` is captured on log markers when inside a traced context,
  enabling log-to-trace correlation.

---

## Source Files

| File | Location | Role |
|------|----------|------|
| `TraceService.groovy` | `grails-app/services/io/xh/hoist/telemetry/trace/` | Central tracing service — SDK lifecycle, exporter pipeline, span API |
| `TraceImplService.groovy` | `grails-app/services/io/xh/hoist/telemetry/trace/` | Internal service hosting W3C context propagation (inbound filter, outbound HTTP, cluster tasks), JDBC DataSource install/uninstall, and the `task {}` PromiseFactory install |
| `HoistFilter.groovy` | `src/main/groovy/io/xh/hoist/` | Wraps every request — restores trace context, enforces auth, creates the SERVER span for tracing, captures any exception, and stamps HTTP semantic-convention attributes |
| `SpanRef.groovy` | `src/main/groovy/io/xh/hoist/telemetry/trace/` | Wrapper around an active Span + Scope with tag/status/error helpers |
| `ObservedRun.groovy` | `src/main/groovy/io/xh/hoist/telemetry/` | Composable builder for combined tracing + logging + metrics |
| `TraceConfig.groovy` | `src/main/groovy/io/xh/hoist/telemetry/trace/` | Typed wrapper around `xhTraceConfig` |
| `ContextPropagatingPromiseFactory.groovy` | `src/main/groovy/io/xh/hoist/telemetry/trace/` | Wraps Grails PromiseFactory to propagate OTel context to `task {}` worker threads |
| `DelegatingOpenTelemetry.groovy` | `src/main/groovy/io/xh/hoist/telemetry/trace/` | Stable `OpenTelemetry` facade that resolves to the current SDK on every tracer/span lookup — lets library instrumentation (e.g. `opentelemetry-jdbc`) capture a reference once and follow SDK rebuilds |
| `SpanProcessingService.groovy` | `grails-app/services/io/xh/hoist/telemetry/trace/impl/` | Singleton terminal span processor. Stamps common attributes (e.g. `user.name`, `xh.isPrimary`) at span start, buffers by traceId, decides keep/drop on flush (keep on error, missing/open root, or rolled probability). Reads `xhTraceConfig` live so config changes don't orphan its in-flight buffer; on keep, hands drained spans to `TraceService.exportSpans()` |
| `ClientSpanData.groovy` | `src/main/groovy/io/xh/hoist/telemetry/trace/` | Adapts client-relayed span JSON into OTel `SpanData` for export through the server pipeline |

---

## TraceService

**File:** `grails-app/services/io/xh/hoist/telemetry/trace/TraceService.groovy`

The central service for distributed tracing. Initialized early in the bootstrap sequence
(after `MetricsService`), it manages the OpenTelemetry SDK, exporter pipeline, and provides
the primary span creation API.

### `withSpan(args, closure)`

Execute a closure within a new trace span. Creates a child span if a parent context exists, or a
root span otherwise. Exceptions are recorded on the span and re-thrown. The closure may optionally
accept a `SpanRef` parameter, which may be further enhanced with tags, or information about errors.
Note that a NoOp span is passed even if tracing is disabled.

For combined tracing + logging + metrics, use `ObservedRun` via `BaseService.observe()` instead.

**Arguments** (passed as named params):

| Key | Type | Description |
|-----|------|-------------|
| `name` | String | Span name (required). |
| `kind` | SpanKind | `INTERNAL` (default), `SERVER`, or `CLIENT`. |
| `tags` | Map | Key-value attributes to set on the span. |
| `caller` | Object | Object making the call, auto-sets the `code.namespace` attribute. |

```groovy
traceService.withSpan(name: 'fetchData', kind: SpanKind.CLIENT, tags: [url: endpoint]) { SpanRef span ->
    def result = httpClient.get(endpoint)
    span.setHttpStatus(result.statusCode)
    result
}
```

### `createSpan(args)`

Creates and starts a new span, returning a `SpanRef` that the caller must close. Use this when
the span lifecycle spans multiple method calls (e.g. interceptors). For simpler cases where a
closure defines the span boundary, prefer `withSpan`.

### `addExporter(exporter)` / `removeExporter(exporter)`

Register additional `SpanExporter` instances to receive both server-generated and client-relayed
spans. Triggers a pipeline rebuild. Use this to add custom export destinations (e.g. Zipkin):

```groovy
traceService.addExporter(
    ZipkinSpanExporter.builder()
        .setEndpoint('http://zipkin:9411/api/v2/spans')
        .build()
)
```

---

## ObservedRun

**File:** `src/main/groovy/io/xh/hoist/telemetry/ObservedRun.groovy`

A composable builder for wrapping a closure with any combination of tracing, logging, and metrics.
Each concern is opt-in via dedicated builder methods, then executed with `run()`. The closure is
wrapped in an onion from outermost to innermost: span → log → timer → counter → closure.

Access via `BaseService.observe()`, which creates a builder pre-configured with the service as
the caller (used for span `code.namespace` and log context).

### Builder methods

| Method | Description |
|--------|-------------|
| `.span(name, kind?, tags?)` | Configure a trace span. |
| `.logInfo(msg)` | Log at INFO via `LogSupport.withInfo`. |
| `.logDebug(msg)` | Log at DEBUG via `LogSupport.withDebug`. |
| `.logTrace(msg)` | Log at TRACE via `LogSupport.withTrace`. |
| `.timer(Timer)` / `.timer(String)` | Record elapsed time on a Micrometer Timer. |
| `.counter(Counter)` / `.counter(String)` | Increment a Micrometer Counter (counts attempts). |
| `.run(closure)` | Terminal — execute with all configured observability. |

### Multi-level logging

When multiple log levels are configured, `ObservedRun` selects the finest enabled level at
`run()` time. This allows callers to specify both a coarse and fine message — the finest enabled
level wins:

```groovy
observe()
    .span(name: 'importData')
    .logInfo('Importing data')
    .logDebug(['Importing data', [source: url, batchSize: n]])
    .run {
        // If DEBUG is enabled: logs with the detailed debug message
        // Otherwise: logs with the shorter info message
    }
```

### Examples

**Span + log + timer** (most common pattern):

```groovy
class PortfolioService extends BaseService {

    Timer generationTimer  // pre-registered Micrometer timer

    private Portfolio generatePortfolio() {
        observe()
            .span(name: 'generatePortfolio')
            .logInfo('Generating Portfolio')
            .timer(generationTimer)
            .run {
                // business logic
            }
    }
}
```

**Span + log only:**

```groovy
observe()
    .span(name: 'generateOrders')
    .logDebug("Generating ${count} orders")
    .run {
        // business logic
    }
```

**Span with SpanRef access:**

```groovy
observe()
    .span(name: 'processOrder', tags: [orderId: id])
    .run { SpanRef span ->
        def result = doWork()
        span.setTag('resultCount', result.size())
        result
    }
```

**Standalone (no BaseService):**

```groovy
ObservedRun.observe(this)
    .span(name: 'myOp')
    .logDebug('Working')
    .run {
        // works from any LogSupport implementor
    }
```

---

## Configuration

### `xhTraceConfig`

| Property | Value |
|----------|-------|
| **Type** | `json` |
| **Default** | See below |
| **Client Visible** | Yes (client reads `enabled` — sampling decisions are made server-side) |
| **Purpose** | Distributed tracing infrastructure configuration. |

**Default value:**

```json
{
    "enabled": false,
    "sampleRate": 1.0,
    "sampleRules": [],
    "traceTimeoutMs": 300000,
    "maxBufferedTraces": 10000,
    "jdbcTracingEnabled": false,
    "otlpEnabled": false,
    "otlpConfig": {}
}
```

| Key | Type | Description |
|-----|------|-------------|
| `enabled` | Boolean | Master switch for tracing. When false, all tracing is no-op. Dynamic. |
| `sampleRate` | Double | Fallback per-trace sampling rate (0.0–1.0) applied when no rule matches the root span. Error traces always export regardless. Dynamic. |
| `sampleRules` | List\<Map\> | Ordered rules matched against the **root span** of each trace. Each rule has a `match` map of tag patterns (plus the reserved `name` key that matches the span's name) and a `sampleRate`. First match wins; unmatched traces use the fallback `sampleRate`. See [Sampling Rules](#sampling-rules) below. Dynamic. |
| `traceTimeoutMs` | Long | Silence threshold before an abandoned trace buffer is force-evicted. Not a trace-duration cap — long-running traces that stay active flush normally when their root ends. Defaults to `300000` (5 minutes). Dynamic. |
| `maxBufferedTraces` | Integer | Cap on in-flight traces buffered by the tail sampler. New traces past the cap are dropped with a WARN log until pressure eases. Defaults to `10000`. Dynamic. |
| `jdbcTracingEnabled` | Boolean | Emit CLIENT spans for all JDBC `DataSource` operations — applies to every pool (primary + any additional Grails datasources). Defaults to `false`. Dynamic. See [JDBC](#outbound-jdbc) below. |
| `otlpEnabled` | Boolean | Enable OTLP span export (HTTP/protobuf). Dynamic. Gated by the `suppressOtlpExport` instance config (defaults to `'true'` in local dev, `'false'` otherwise). |
| `otlpConfig` | Map | OTLP exporter config (e.g. `{"endpoint": "http://localhost:4318/v1/traces"}`). |

When `xhTraceConfig` is updated, the exporter pipeline is torn down and recreated. This is
handled by `clearCaches()` responding to the `xhConfigChanged` event.

---

## Export Configuration

### OTLP

When `otlpEnabled: true`, spans are exported via HTTP/protobuf to an OTLP-compatible backend
(e.g. Jaeger, Grafana Tempo, Datadog). Configure the endpoint via `otlpConfig`:

```json
{
    "enabled": true,
    "sampleRate": 1.0,
    "otlpEnabled": true,
    "otlpConfig": {
        "endpoint": "http://localhost:4318/v1/traces",
        "timeout": "30000"
    }
}
```

### Custom exporters

Applications can register additional exporters via `traceService.addExporter()`. These
receive both server-generated and client-relayed spans.

---

## Sampling

Hoist uses **tail-based sampling**: all spans for a locally-rooted trace are buffered in memory
until the root span ends (or the trace times out), and the keep/drop decision is made once for
the entire trace. This has two consequences application developers should know:

- **Error traces are always preserved in full.** If any span in a trace ends in error, every
  span in that trace (client and server, parents and children) is exported, regardless of the
  configured sample rate.
- **Sample rules apply at the trace level.** Rules match the root span's name and tags — not
  individual spans within the trace. This is more intuitive: you're asking "should we keep this
  request's trace?" rather than deciding span-by-span.

### Propagation semantics

- **Outbound** from a locally-rooted trace: the outgoing `traceparent` header carries
  `sampled=1` while we are recording. Our tail decision happens after the HTTP call has been
  made, so downstream services see `sampled=1` even for traces we might later drop — an
  acceptable trade for keeping full-trace context on errors.
- **Inbound** with `sampled=1`: inherit keep — record and forward, bypass the tail buffer.
- **Inbound** with `sampled=0`: inherit drop — do not record locally. Standard W3C semantics;
  if upstream is dropping, there's no point producing orphan spans in the backend.

### Configuration

Add rules to the `sampleRules` array in `xhTraceConfig`:

```json
{
    "enabled": true,
    "sampleRate": 0.1,
    "sampleRules": [
        {"match": {"name": "GET health/*"}, "sampleRate": 0},
        {"match": {"xh.source": "hoist"}, "sampleRate": 0.01},
        {"match": {"user.name": "jsmith"}, "sampleRate": 1.0}
    ]
}
```

In this example: traces rooted on health-check requests drop entirely (unless they error),
traces rooted on framework-generated spans keep at 1%, traces rooted by user `jsmith` always
keep, and everything else falls back to the 10% default.

### Pattern matching

String tag values support simple glob patterns:

| Pattern | Matches |
|---------|---------|
| `*` | Any value |
| `foo*` | Values starting with `foo` |
| `*foo` | Values ending with `foo` |
| `*foo*` | Values containing `foo` |
| `foo` | Exact match |

Non-string values (numbers, booleans) use strict equality.

### Decision flow

1. Every span for a locally-rooted trace is recorded and accumulated in a per-trace buffer.
2. When the root span ends, the buffer is evaluated:
   - If any buffered span ended in error → **keep all**, export every span.
   - Otherwise → match `sampleRules` against the root span's name and tags; the first rule whose
     `match` entries all match produces the `sampleRate`; otherwise use the fallback.
3. One `nextDouble()` roll against that rate decides keep or drop for the entire trace.
4. Traces whose root never ends (async timers, WebSocket pushes, leaked spans) are evicted by a
   periodic sweeper after `traceTimeoutMs` of silence.
5. Client-submitted spans are deposited into the same buffer by traceId and participate in the
   same decision — making client-to-server traces a single all-or-nothing unit.

---

## Built-in Instrumentation

### Request spans (HoistFilter)

`HoistFilter` extracts incoming W3C `traceparent` headers from the request, restoring the
client's trace context. After auth passes, the filter wraps `chain.doFilter` in a SERVER
span that becomes a child of the client span when a traceparent was present.

- **Name:** starts as `{METHOD} {uri}` at span creation (best info pre-dispatch); updated
  to `{METHOD} {controller}/{action}` (e.g. `GET portfolio/positions`) once the controller
  resolves, before the span ends.
- **Attributes:** `http.request.method`, `http.route`, `url.path`, `url.scheme`,
  `server.address`, `server.port`, `client.address`, `user_agent.original`,
  `http.response.status_code`.
- Exceptions thrown during dispatch are recorded on the span via `recordException` before
  Hoist's `ExceptionHandler` renders the error response.

> **Note on sample-rule matching by name**: the head-sampling decision happens at span
> creation time, when the name is the URI form. If you want to match `sampleRules` against
> the route form (`controller/action`), match against the URI pattern instead (e.g.
> `name: 'GET /xh/health/*'`).

### Outbound HTTP (JSONClient)

All outbound HTTP calls via `JSONClient` automatically:
1. Create a CLIENT span named with the HTTP method (e.g. `POST`)
2. Inject W3C `traceparent` headers onto the outbound request
- **Attributes:** `http.request.method`, `url.full`, `server.address`, `server.port`,
  `http.response.status_code`, `xh.source=hoist`

### Proxy requests (BaseProxyService)

Proxied requests via `BaseProxyService` automatically:
1. Create a CLIENT span named with the HTTP method (e.g. `GET`)
2. Inject W3C trace context onto the proxied request
- **Attributes:** `http.request.method`, `url.full`, `server.address`,
  `http.response.status_code`, `xh.source=hoist`

### Outbound JDBC

At startup, `TraceImplService.init()` walks down each `DataSource`'s proxy chain to the
underlying raw pool and swaps it for an `OpenTelemetryDataSource` wrap. Wrapping at the
*bottom* of the chain means every consumer sharing the chain — Spring DI, direct JDBC,
Hibernate/GORM — is instrumented by a single wrap. The wrap is a no-op unless
`jdbcTracingEnabled` is `true` on `xhTraceConfig`, so toggling JDBC tracing is a runtime
config change — no restart or re-wrapping needed.

When enabled, CLIENT spans are emitted for each connection acquire and statement execution,
parented under whatever span is active at query time — typically the request span created by
`TraceInterceptor`, but also timer tasks, `withSpan` blocks, and cluster tasks.

- **Name:** `{operation} {schema}.{table}` where derivable, or a generic operation name
  (e.g. `SELECT xh_app_config`).
- **Attributes:** standard OTel DB semconv — `db.system`, `db.namespace`, `db.statement`,
  `server.address`, `server.port`, etc.

**Enabling.**

```json
{
    "enabled": true,
    "jdbcTracingEnabled": true
}
```

The master `enabled` flag takes precedence regardless.

**Multi-datasource apps.** Grails apps configured with multiple datasources
(`dataSource_reporting`, `sessionFactory_reporting`, etc.) are handled automatically —
`TraceImplService` iterates every Spring `DataSource` bean and every Hibernate
`SessionFactory`, covering both direct-JDBC and GORM paths. The flag applies uniformly to
all pools; there's no per-pool gating.

---

## Context Propagation

### Cluster tasks

`ClusterTask` captures the current `traceparent` string at construction time and restores
it on the remote instance before execution. This means `runOnAllInstances(...)` calls from
within a traced request produce child spans on remote instances.

The `traceparent` is a plain String field that serializes naturally with Kryo. When no active
trace context exists at construction time, `traceparent` is null and context restoration is
skipped.

### Thread context propagation

At startup, Hoist installs a `ContextPropagatingPromiseFactory` that wraps the default Grails
`PromiseFactory`. This ensures that every `task {}` call — the primary async dispatch
mechanism in Hoist — automatically carries the calling thread's OTel trace context to the
worker thread.

This covers all Grails `task {}` usage across the codebase (e.g. `asyncEach`, `LdapService`,
`MonitorEvalService`, `TrackService`) without any per-call-site changes.

### Client-to-server propagation

The client-side `TraceService` sends a `traceparent` header on every fetch request.
`HoistFilter` extracts this header and restores the trace context, so the SERVER span created
by `TraceInterceptor` becomes a child of the client's span — producing end-to-end traces from
browser interaction through server processing.

---

## Client Span Relay

Browser-generated spans are batched and posted to the `xh/submitSpans` endpoint, which routes
to `TraceService.submitClientSpans()`. That method converts each entry into an OTel `SpanData`
(via `ClientSpanData`) — preserving the original trace/span IDs — and deposits it into the
tail-sampling buffer, where it merges with any concurrent server-side spans for the same trace.
Client and server spans then participate in a single unified keep/drop decision, guaranteeing
that an error anywhere in the trace preserves the full client-plus-server span tree.

Clients submit **all** spans — no client-side sampling. The server is the single source of
truth for which traces are exported. Client-only traces (no server counterpart) are flushed by
the sweeper when `traceTimeoutMs` elapses with no new activity.

---

## Log Correlation

`LogSupportMarker` captures the active `traceId` at logging time. The default
`LogSupportConverter` appends `traceId=...` to log output for ERROR-level and above, enabling
correlation between logs and traces in observability platforms.

---

## Resource Attributes

All spans include these resource attributes identifying the source instance:

| Attribute | Value |
|-----------|-------|
| `service.name` | Application code (e.g. `myApp`) |
| `service.instance.id` | Cluster instance name (e.g. `inst1`) |
| `deployment.environment.name` | Hoist AppEnvironment (e.g. `PRODUCTION`) |
| `service.version` | Application version |
