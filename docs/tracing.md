# Distributed Tracing

## Overview

Hoist-core provides distributed tracing built on [OpenTelemetry](https://opentelemetry.io/), using
the OTel SDK directly for span processing and export. The system supports OTLP export, is configured
dynamically via soft config, and is visible in the Hoist Admin console.

Tracing is **disabled by default** and has negligible overhead when disabled — all public methods
delegate to no-op implementations, so no null checks are needed in application code.

### Key capabilities

- **Central service** — `TraceService` manages the OpenTelemetry SDK lifecycle, exporter
  pipeline, and provides the `withSpan` API for instrumenting business logic.
- **BaseService convenience** — `withSpan(name, tags) { ... }` is available directly on any
  service, mirroring patterns like `getUser()` that delegate to framework services.
- **Automatic request spans** — `HoistFilter` creates SERVER spans for all HTTP requests,
  with method, URL, status code, and username attributes.
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
- **Log correlation** — `traceId` and `spanId` are added to log metadata when inside a traced
  context, enabling log-to-trace correlation.
- **Admin visibility** — `TracingAdminController` exposes cluster-wide tracing status and
  configuration for operational monitoring.

---

## Source Files

| File | Location | Role |
|------|----------|------|
| `TraceService.groovy` | `grails-app/services/io/xh/hoist/telemetry/` | Central tracing service — SDK lifecycle, exporter pipeline, span API, context propagation |
| `TraceConfig.groovy` | `src/main/groovy/io/xh/hoist/telemetry/` | Typed wrapper around `xhTraceConfig` |
| `ClientTraceService.groovy` | `grails-app/services/io/xh/hoist/telemetry/` | Receives client-side spans and relays through server export pipeline |
| `ContextPropagatingPromiseFactory.groovy` | `src/main/groovy/io/xh/hoist/telemetry/` | Wraps Grails PromiseFactory to propagate OTel context to `task {}` worker threads |
| `TracingAdminService.groovy` | `grails-app/services/io/xh/hoist/admin/` | Admin status reporting |
| `TracingAdminController.groovy` | `grails-app/controllers/io/xh/hoist/admin/cluster/` | REST endpoints for admin tracing UI |

---

## TraceService

**File:** `grails-app/services/io/xh/hoist/telemetry/TraceService.groovy`

The central service for distributed tracing. Initialized early in the bootstrap sequence
(after `MetricsService`), it manages the OpenTelemetry SDK, exporter pipeline, and provides
the primary span creation API.

### `withSpan(name, tags, closure)`

The primary API for instrumenting application code. Creates a child span if a parent context
exists, or a root span otherwise. Exceptions are recorded on the span and re-thrown.

```groovy
class OrderService extends BaseService {

    def processOrder(Map order) {
        withSpan('processOrder', [orderId: order.id]) {
            // Business logic here — nested withSpan calls create child spans
            def validated = withSpan('validateOrder') {
                validateOrder(order)
            }
            withSpan('persistOrder') {
                saveToDatabase(validated)
            }
        }
    }
}
```

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

### `wrapContext(closure)`

Captures the current trace context and returns a wrapper closure that restores it. Use this
when passing work to raw `ExecutorService` instances or other non-Grails async boundaries:

```groovy
def wrapped = traceService.wrapContext {
    // This code will run with the original trace context
    processInBackground()
}
executor.submit(wrapped)
```

Note: Grails `task {}` calls do **not** need manual wrapping — context propagation is handled
automatically (see [Thread Context Propagation](#thread-context-propagation) below).

### `otelSdk`

For advanced use cases, `TraceService` exposes the underlying `OpenTelemetrySdk` instance
(null when disabled). Available for custom propagation, baggage, or other direct OTel API access.

---

## BaseService.withSpan

Every `BaseService` subclass has a `withSpan` convenience method that delegates to
`TraceService.withSpan`:

```groovy
class MyService extends BaseService {
    def doWork() {
        withSpan('doWork') {
            // Traced automatically
        }
    }
}
```

This parallels existing convenience patterns like `getUser()` → `identityService.user`.

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
| `sampleRate` | Double | Sampling rate from 0.0 to 1.0. Applied via `TraceIdRatioBasedSampler`. Dynamic. |
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

### Request spans (HoistFilter)

Every HTTP request passing through `HoistFilter` creates a SERVER span with:
- **Name:** `{METHOD} {requestURI}` (e.g. `GET /api/orders`)
- **Attributes:** `http.method`, `http.url`, `http.status_code`, `username`, `source=hoist`
- **Context propagation:** incoming `traceparent` headers are honored, joining the request
  to an existing distributed trace.

### Outbound HTTP (JSONClient)

All outbound HTTP calls via `JSONClient` automatically:
1. Inject W3C `traceparent` headers onto the outbound request
2. Create a CLIENT span: `HTTP {METHOD}` with `http.url` and `source=hoist`

### Proxy requests (BaseProxyService)

Proxied requests via `BaseProxyService` automatically:
1. Inject W3C trace context onto the proxied request
2. Create a CLIENT span: `PROXY {endpoint}` with `source=hoist`

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

For raw `ExecutorService` usage outside of Grails tasks, use `traceService.wrapContext()`.

### Client-to-server propagation

The client-side `TraceService` sends a `traceparent` header on every fetch request. The
server's `HoistFilter` extracts this header and creates a SERVER span as a child of the
client's span, producing end-to-end traces from browser interaction through server processing.

---

## Client Span Relay

Browser-generated spans are batched and posted to the `xh/submitSpans` endpoint. The server's
`ClientTraceService` converts these into OTel `SpanData` objects — preserving the original
trace/span IDs — and exports them through the same pipeline as server-generated spans. This
means client and server spans appear as a coherent distributed trace in the collector.

---

## Log Correlation

When inside a traced context, `LogSupport.createMarker()` automatically adds `traceId` and
`spanId` to the log metadata map. These appear in structured log output and can be used to
correlate logs with traces in observability platforms.

No changes to default logback patterns are required — the values are present in the metadata
map that `LogSupportMarker` serializes. Applications using custom logback patterns can access
them via `%X{traceId}` if they add MDC support.

---

## Admin Console

### Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/tracingAdmin/config` | GET | Cluster-wide tracing status (enabled, config) |

Requires `HOIST_ADMIN_READER` role.

---

## Resource Attributes

All spans include these resource attributes identifying the source instance:

| Attribute | Value |
|-----------|-------|
| `service.name` | Application code (e.g. `myApp`) |
| `service.instance.id` | Cluster instance name (e.g. `inst1`) |
| `deployment.environment` | Hoist AppEnvironment (e.g. `PRODUCTION`) |
| `service.version` | Application version |
