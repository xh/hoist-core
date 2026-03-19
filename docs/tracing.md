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
- **Automatic request spans** — `TraceInterceptor` creates SERVER spans for controller actions.
  `HoistFilter` extracts incoming W3C `traceparent` headers so request spans join existing traces.
- **Export** — OTLP (HTTP/protobuf) export configured via soft config. Applications can register
  additional exporters (e.g. Zipkin) via `addExporter()`.
- **W3C trace propagation** — incoming `traceparent` headers are honored; outbound HTTP calls
  via `JSONClient` and `BaseProxyService` inject trace context automatically.
- **Cluster context propagation** — `ClusterTask` captures and restores trace context across
  Hazelcast remote execution, maintaining parent-child span relationships.
- **Thread context propagation** — Grails `task {}` calls automatically carry the current trace
  context to worker threads via `ContextPropagatingPromiseFactory`.
- **Client span relay** — Browser-generated spans are submitted to `xh/submitSpans` and exported
  through the same server-side pipeline, producing end-to-end client-to-server traces.
- **Log correlation** — `traceId` is captured on log markers when inside a traced context,
  enabling log-to-trace correlation.

---

## Source Files

| File | Location | Role |
|------|----------|------|
| `TraceService.groovy` | `grails-app/services/io/xh/hoist/telemetry/` | Central tracing service — SDK lifecycle, exporter pipeline, span API, context propagation |
| `TraceInterceptor.groovy` | `grails-app/controllers/io/xh/hoist/telemetry/` | Creates SERVER spans for controller actions with HTTP semantic convention attributes |
| `SpanRef.groovy` | `src/main/groovy/io/xh/hoist/telemetry/` | Wrapper around an active Span + Scope with tag/status/error helpers |
| `ObservedRun.groovy` | `src/main/groovy/io/xh/hoist/telemetry/` | Composable builder for combined tracing + logging + metrics |
| `TraceConfig.groovy` | `src/main/groovy/io/xh/hoist/telemetry/` | Typed wrapper around `xhTraceConfig` |
| `ClientTraceService.groovy` | `grails-app/services/io/xh/hoist/telemetry/` | Receives client-side spans and relays through server export pipeline |
| `ContextPropagatingPromiseFactory.groovy` | `src/main/groovy/io/xh/hoist/telemetry/` | Wraps Grails PromiseFactory to propagate OTel context to `task {}` worker threads |

---

## TraceService

**File:** `grails-app/services/io/xh/hoist/telemetry/TraceService.groovy`

The central service for distributed tracing. Initialized early in the bootstrap sequence
(after `MetricsService`), it manages the OpenTelemetry SDK, exporter pipeline, and provides
the primary span creation API.

### `withSpan(args, closure)`

Execute a closure within a new trace span. Creates a child span if a parent context exists, or a
root span otherwise. Exceptions are recorded on the span and re-thrown. The closure may optionally
accept a `SpanRef` parameter (null when tracing is disabled).

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
    span?.setHttpStatus(result.statusCode)
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

### Context propagation methods

| Method | Description |
|--------|-------------|
| `injectContext(request)` | Inject W3C trace context onto an outbound Apache `HttpUriRequestBase`. |
| `captureTraceparent()` | Capture the current trace context as a W3C `traceparent` string. |
| `restoreContextFromTraceparent(traceparent)` | Restore a previously captured traceparent as current context. Returns a `Scope` to close. |
| `restoreContextFromRequest(request)` | Extract W3C trace context from an incoming `HttpServletRequest`. Returns a `Scope` to close. |

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
        span?.setTag('resultCount', result.size())
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
| **Client Visible** | Yes (client reads `enabled` and `sampleRate` for browser tracing) |
| **Purpose** | Distributed tracing infrastructure configuration. |

**Default value:**

```json
{
    "enabled": false,
    "sampleRate": 1.0,
    "otlpEnabled": false,
    "otlpConfig": {}
}
```

| Key | Type | Description |
|-----|------|-------------|
| `enabled` | Boolean | Master switch for tracing. When false, all tracing is no-op. Dynamic. |
| `sampleRate` | Double | Sampling rate from 0.0 to 1.0. Uses a deterministic trace-ID-based algorithm. Dynamic. |
| `otlpEnabled` | Boolean | Enable OTLP span export (HTTP/protobuf). Dynamic. |
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

## Built-in Instrumentation

### Request spans (TraceInterceptor)

`HoistFilter` extracts incoming W3C `traceparent` headers from the request, restoring the
client's trace context. `TraceInterceptor` then creates a SERVER span for each controller
action, which becomes a child of the client span when a traceparent was present.

- **Name:** `{METHOD} {controller}/{action}` (e.g. `GET portfolio/positions`)
- **Attributes:** `http.request.method`, `http.route`, `url.path`, `url.scheme`,
  `server.address`, `http.response.status_code`, `source=hoist`
- The `submitSpans` action is excluded to avoid recursive tracing.

### Outbound HTTP (JSONClient)

All outbound HTTP calls via `JSONClient` automatically:
1. Create a CLIENT span named with the HTTP method (e.g. `POST`)
2. Inject W3C `traceparent` headers onto the outbound request
- **Attributes:** `http.request.method`, `url.full`, `server.address`,
  `http.response.status_code`, `source=hoist`

### Proxy requests (BaseProxyService)

Proxied requests via `BaseProxyService` automatically:
1. Create a CLIENT span named with the HTTP method (e.g. `GET`)
2. Inject W3C trace context onto the proxied request
- **Attributes:** `http.request.method`, `url.full`, `server.address`,
  `http.response.status_code`, `source=hoist`

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

At startup, `TraceService` installs a `ContextPropagatingPromiseFactory` that wraps the
default Grails `PromiseFactory`. This ensures that every `task {}` call — the primary async
dispatch mechanism in Hoist — automatically carries the calling thread's OTel trace context
to the worker thread.

This covers all Grails `task {}` usage across the codebase (e.g. `asyncEach`, `LdapService`,
`MonitorEvalService`, `TrackService`) without any per-call-site changes.

### Client-to-server propagation

The client-side `TraceService` sends a `traceparent` header on every fetch request.
`HoistFilter` extracts this header and restores the trace context, so the SERVER span created
by `TraceInterceptor` becomes a child of the client's span — producing end-to-end traces from
browser interaction through server processing.

---

## Client Span Relay

Browser-generated spans are batched and posted to the `xh/submitSpans` endpoint. The server's
`ClientTraceService` converts these into OTel `SpanData` objects — preserving the original
trace/span IDs — and exports them through the same pipeline as server-generated spans. This
means client and server spans appear as a coherent distributed trace in the collector.

The same deterministic trace-ID-based sampling is applied to client spans, ensuring consistent
sampling decisions across the full trace.

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
| `deployment.environment` | Hoist AppEnvironment (e.g. `PRODUCTION`) |
| `service.version` | Application version |
