# Distributed Tracing for Hoist Core

## Goal

Design and implement distributed tracing support in **hoist-core**, built on
**Micrometer Tracing** and **OpenTelemetry** (OTLP export). The implementation must follow the
patterns already established by `MetricsService` — soft-config-driven, admin-visible, and simple for
the common case — while adding trace context propagation across threads, cluster instances, and
outbound REST calls.

---

## Context: Existing Metrics Infrastructure (follow these patterns closely)

Hoist already ships a Micrometer-based metrics system. The new tracing work should mirror its
architecture:

| Concern | Metrics (existing) | Tracing (new) |
|---|---|---|
| **Central service** | `MetricsService` (`io.xh.hoist.telemetry`) | New `TracingService` in same package |
| **Typed config** | `MetricsConfig` (`xhMetricsConfig` soft config) | New `TracingConfig` (`xhTracingConfig` soft config) |
| **Export sinks** | `CompositeMeterRegistry` with Prometheus + OTLP sub-registries | OpenTelemetry `SpanExporter` pipeline (OTLP primary; Zipkin optional) |
| **Admin controller** | `MetricsAdminController` — lists meters, toggles publishing | New `TracingAdminController` — shows config, status, sample recent spans |
| **Namespace/tags** | Auto-prefixed names, default `application`/`instance`/`source` tags | Equivalent resource attributes on all spans |
| **Cluster awareness** | `instance` tag, cluster-wide Prometheus scrape | `instance` resource attribute; context propagated in Hazelcast calls |
| **Config reload** | `clearCaches()` → `syncPublishRegistries()` | `clearCaches()` → rebuild exporter pipeline |

Key source files to study:
- `MetricsService.groovy` — `grails-app/services/io/xh/hoist/telemetry/`
- `MetricsConfig.groovy` — `src/main/groovy/io/xh/hoist/telemetry/`
- `MetricsAdminController.groovy` — `grails-app/controllers/io/xh/hoist/admin/cluster/`

---

## Requirements

### 1. Simple, Declarative API for Creating and Joining Spans

Provide a minimal API for both **service** (server) and **controller** code to participate in
traces. Prioritize the simple case — a method-level span with automatic lifecycle — while allowing
manual span creation for advanced cases.

```groovy
// Simple: wrap a closure — span name, tags, error handling all automatic
withSpan('loadPortfolio', [userId: username]) {
    // ... business logic — child spans nest automatically via thread context
    logInfo('Loaded 42 positions')  // traceId + spanId auto-injected via MDC
}

// On a timer or scheduled task — ensure trace context is fresh
createTimer(name: 'syncPositions', runFn: this.&doSync, interval: 5 * MINUTES)
// Timer infrastructure should auto-wrap each invocation in a root span

// Annotation-driven (optional, if clean to implement via Grails interceptor)
@WithSpan('loadPortfolio')
def loadPortfolio() { ... }
```

**`BaseService.withSpan`**: Add a `withSpan(String name, Map tags = [:], Closure c)` convenience
method on `BaseService` that delegates to `tracingService.withSpan(...)`. This follows the same
pattern as `logInfo()` / `withInfo()` being available directly on services via `LogSupport` —
developers should not need to inject or reference `tracingService` for the common case.

`withSpan` should automatically handle:
- **Error recording**: If the closure throws, mark the span as errored and record the exception.
- **MDC correlation**: Set `traceId` and `spanId` on the SLF4J MDC on entry, restore on exit.
  Any `logInfo()` / `logDebug()` / etc. call (via `LogSupport`) inside the closure will
  automatically include trace context in the log output, enabling click-through from traces
  to logs (and vice versa) in Datadog/Grafana.

The API should feel natural alongside existing patterns:
```groovy
metricsService.registry.counter('portfolio.loads').increment()  // metrics (via registry)
withSpan('loadPortfolio') { ... }                               // tracing (direct on service)
withInfo('Loading portfolio') { ... }                           // logging (direct on service)
```

### 2. Coherent Observability API

Tracing joins metrics under a unified observability umbrella. Ensure:
- `TracingService` lives in `io.xh.hoist.telemetry` alongside `MetricsService`.
- Shared config patterns: `xhTracingConfig` mirrors `xhMetricsConfig` structure.
- `BaseService.withSpan()` provides direct access to tracing (see #1), paralleling how
  `LogSupport` provides `withInfo()` / `logInfo()`. No additional trait or abstraction needed.

### 3. Soft-Config-Driven Exporter Configuration

All exporter/collector configuration via `xhTracingConfig` soft config (JSON), dynamically
reloadable via `clearCaches()`. Minimum schema:

```json
{
  "enabled": true,
  "sampleRate": 1.0,
  "otlpEnabled": true,
  "otlpConfig": {
    "endpoint": "http://otel-collector:4318/v1/traces",
    "headers": {},
    "timeout": "10s"
  },
  "zipkinEnabled": false,
  "zipkinConfig": {
    "endpoint": "http://zipkin:9411/api/v2/spans"
  }
}
```

Reloading should tear down and rebuild the exporter pipeline (same pattern as
`MetricsService.syncPublishRegistries()`).

### 4. Admin API

A simplified admin view, analogous to the metrics admin tab:

- **`TracingAdminController`** in `grails-app/controllers/io/xh/hoist/admin/cluster/`
  - `GET config` — current `TracingConfig` and status (enabled, exporter health).
  - `GET recentSpans` — small ring buffer of recently completed spans (for troubleshooting only —
    main analysis should happen in Datadog/Grafana/Zipkin).
- **`TracingService.getAdminStats()`** — return config summary, span counts, exporter status.
- Keep this minimal. The admin console is for quick health checks, not trace analysis.

### 5. Built-In Hoist Internal Tracing

Tracing works in three layers. Hoist provides the structural root and outbound propagation
automatically; application developers add the business-level spans via `withSpan`.

### Layer 1: Request Root Span (automatic, Hoist framework)

A single **server span per incoming HTTP request**, created in `HoistFilter` / interceptor layer.
This is the standard foundation of distributed tracing — it provides the parent context that all
business spans nest under, and captures the structural envelope:

- HTTP method, path, status code
- Username (from `IdentityService`)
- Elapsed time
- Joins an existing trace if an inbound `traceparent` header is present; starts a new root
  trace otherwise

Tagged `source: 'hoist'`.

### Layer 2: Business Operation Spans (manual, application developers)

Developers instrument the high-level business operations that matter:

```groovy
withSpan('rebalancePortfolio', [fund: fundId]) {
    def positions = loadPositions()         // could contain its own child span
    def trades = calculateTrades(positions) // and another
    executeTrades(trades)                   // outbound HTTP auto-propagates context
}
```

These nest as children of the request root span.

### Layer 3: Outbound HTTP Spans (automatic, Hoist framework)

Spans on `JSONClient` / `BaseProxyService` calls that automatically inject W3C `traceparent`
headers, connecting the trace to downstream services. Tagged `source: 'hoist'`.

### What NOT to auto-instrument

Timer ticks, cache loads, cluster operations, and other high-frequency internals — these are
already covered by Hoist's metrics infrastructure. Do not auto-span them.

### 6. Outbound Trace Context Propagation (REST)

When Hoist makes outbound HTTP calls via `JSONClient` / `BaseProxyService`:
- Inject W3C `traceparent` / `tracestate` headers automatically.
- Use OpenTelemetry's `W3CTraceContextPropagator` (the standard).
- This ensures downstream services receiving REST calls can join the trace.

When Hoist *receives* requests:
- Extract `traceparent` from incoming request headers to join an existing trace, or start a new
  root span if absent.

### 7. Thread Context Propagation

Trace context must follow work across threads. Key scenarios in Hoist:

- **Grails `Promise`** (wraps `java.util.concurrent.Future`) — ensure span context is captured at
  creation and restored when the promise executes on a worker thread.
- **`Executors` / thread pools** — provide a context-aware executor wrapper or document how to use
  OpenTelemetry's `Context.taskWrapping()` with standard executors.
- **Hazelcast distributed execution** (`runOnAllInstances`, `runOnInstance`, `runOnPrimary`) — the
  serialized `Callable` should carry trace context so the receiving instance can create a child span
  linked to the originating trace.
- **`Timer` callbacks** — each timer tick is a new root trace (no parent), but if a timer callback
  dispatches sub-tasks, those should be children.

Consider providing a utility or trait method like:
```groovy
// Wraps a closure so it carries the current trace context
def contextualClosure = tracingService.wrapContext { ... }
```

### 8. Library Choices

Stick with standard, well-maintained libraries:

| Library | Purpose |
|---|---|
| `io.micrometer:micrometer-tracing` | Facade for tracing (mirrors micrometer-core for metrics) |
| `io.micrometer:micrometer-tracing-bridge-otel` | Bridges Micrometer Tracing to OpenTelemetry |
| `io.opentelemetry:opentelemetry-sdk` | Core OTel SDK for span processing and export |
| `io.opentelemetry:opentelemetry-exporter-otlp` | OTLP exporter (gRPC or HTTP) |
| `io.opentelemetry:opentelemetry-exporter-zipkin` | Zipkin exporter (optional) |
| `io.opentelemetry:opentelemetry-context` | Context propagation utilities |

The Micrometer Tracing facade keeps application code decoupled from OTel internals — same
philosophy as Micrometer for metrics.

### 9. Data Format Compatibility

Ensure traces are produced in standard OTLP format so they work out-of-the-box with:
- **Datadog** — via Datadog Agent's OTLP ingest endpoint or Datadog Exporter.
- **Grafana Tempo** — native OTLP support.
- **Zipkin** — via the Zipkin exporter or OTLP-to-Zipkin translation.

### Standard Attributes

All spans receive a consistent set of attributes. These split into two levels, following
OpenTelemetry conventions:

**Resource attributes** (set once on TracerProvider — every span inherits automatically):

| Attribute | Source | Notes |
|---|---|---|
| `service.name` | `appCode` | Equivalent to metrics' `application` tag |
| `service.instance.id` | `instanceName` | Equivalent to metrics' `instance` tag |
| `deployment.environment` | Hoist environment | Maps from Development/Staging/Production |
| `service.version` | App version from build | Correlates traces with deployments |

These are OTel semantic conventions — Datadog, Grafana, and Zipkin understand them natively for
filtering and grouping. Do not duplicate them as per-span attributes.

**Span attributes** (set per-span, contextual):

| Attribute | When | Notes |
|---|---|---|
| `source` | Always | `'hoist'` or `'app'` — mirrors the metrics `source` tag convention |
| `username` | Request-scoped spans | From `IdentityService`. Not available on timer/background spans. |

### 10. Hazelcast / Multi-Instance Considerations

- Each instance produces its own spans with its own `instance` resource attribute.
- Trace context must be propagated across Hazelcast distributed execution calls (see #7).
- The admin endpoint should be able to report tracing status from all instances (fan-out pattern,
  same as `MetricsService.prometheusData()`).
- Sampling decisions should be consistent across instances for a given trace (use
  `TraceIdRatioBased` sampler with the configured `sampleRate`).

### 11. Simplicity and Extensibility

- **Simple case**: Drop-in. Enable `xhTracingConfig.enabled = true`, point `otlpConfig.endpoint` at
  your collector, and Hoist's built-in spans start flowing. App developers use
  `withSpan(...)` for custom spans.
- **Extensible**: Applications can access the underlying `OpenTelemetry` SDK instance from
  `TracingService` for advanced use cases (custom samplers, additional exporters, baggage).
- **Do not over-design**: No distributed trace aggregation, custom trace storage, or complex UI.
  Hoist produces traces; external tools analyze them.

---

## Implementation Standards

All new code must follow hoist-core's established conventions. Study the existing codebase —
particularly `MetricsService`, `MetricsConfig`, `MetricsAdminController`, and
`MetricsAdminService` — as the direct template for this work.

### Code Style and Conventions

- **`@CompileStatic`** on all new classes unless dynamic Groovy is specifically required (as
  `MetricsService` does).
- **`LogSupport`** for all logging — use `logDebug()`, `logInfo()`, `logWarn()`, `logError()`,
  `withInfo()`, `withDebug()`. Never raw SLF4J loggers.
- **Javadoc** on public API methods and properties following the style in `MetricsService`
  (Javadoc `{@link}` references, `{@code}` for inline code).
- **Hoist copyright header** on all new files (see any existing file for the template).
- **Package**: `io.xh.hoist.telemetry` for service and config classes, matching metrics.

### Service Patterns (follow `MetricsService` exactly)

- Extend `BaseService`. Use `init()` for startup, `clearCaches()` for config-driven reload.
- **`clearCachesConfigs`**: Declare `static clearCachesConfigs = ['xhTracingConfig']` so the
  service auto-reloads when the config changes — same pattern as `MetricsService`.
- **`getAdminStats()`**: Return config summary via `configForAdminStats('xhTracingConfig')`.
- **Config access**: Private `getConfig()` method returning a typed `TracingConfig` from
  `configService.getMap('xhTracingConfig')` — same as `MetricsService.getConfig()`.
- **No extra state**: Do not store state that can be derived from config or the OTel SDK.
  Follow `MetricsService`'s pattern of keeping only the registry/exporter references needed
  for teardown and rebuild.

### Controller Patterns (follow `MetricsAdminController` exactly)

- Extend `BaseController`. Use `@AccessRequiresRole('HOIST_ADMIN_READER')` at the class level.
- Inject services via `def tracingService`, `def tracingAdminService` — standard Grails DI.
- Use `renderJSON()` for responses, `parseRequestJSON()` for request bodies.
- Place in `grails-app/controllers/io/xh/hoist/admin/cluster/`.

### Admin Service Patterns (follow `MetricsAdminService` exactly)

- Separate admin read logic into a dedicated `TracingAdminService` in
  `grails-app/services/io/xh/hoist/admin/` — keeps the main `TracingService` focused.
- The admin service reads from `TracingService`, the controller delegates to the admin service.
  Controllers stay thin.

### Config Bootstrap (follow `xhMetricsConfig` exactly)

- Add `xhTracingConfig` entry in `BootStrap.groovy` alongside existing `xhMetricsConfig`,
  using the same structure: `valueType: 'json'`, `groupName: 'xh.io'`, `defaultValue` with
  tracing disabled, and a descriptive `note`.

### DRY and Readability

- **No duplicated logic**: If `TracingService` and `MetricsService` share patterns (e.g.
  `prefixKeys` for config mapping), extract shared utilities rather than copy-pasting. Check
  whether `MetricsService.prefixKeys()` can be promoted to a shared location.
- **Minimal new abstractions**: Do not create helper classes, traits, or utilities unless they
  eliminate clear duplication. Three similar lines of code is better than a premature abstraction.
- **No extra state**: Derive values from config or SDK state at read time rather than caching
  them in fields. Only store references needed for lifecycle management (exporter teardown).
- **Readable Groovy**: Prefer Groovy idioms (`def`, `collectEntries`, `with` blocks) where they
  aid readability. Use explicit types on public API signatures.

### Documentation

- New `docs/tracing.md` following the structure and conventions of `docs/metrics.md`.
- Update `docs/doc-registry.json` and `docs/README.md` to include the new doc.
- Reference the AGENTS.md conventions for documentation style.

---

## Deliverables

1. **`TracingService`** — Central service: OTel SDK lifecycle, span API, config reload.
2. **`TracingConfig`** — Typed config class for `xhTracingConfig`.
3. **`TracingAdminController`** + supporting admin service — Admin visibility.
4. **Built-in instrumentation** — Request root spans, outbound HTTP spans with context propagation.
5. **Context propagation** — Thread/Promise/Hazelcast wrappers.
6. **Gradle dependencies** — Added to hoist-core's `build.gradle`.
7. **Default soft config entries** — Bootstrap `xhTracingConfig` with sensible defaults (disabled).
8. **Documentation** — Doc in `docs/tracing.md` following the existing documentation conventions.

---

## Out of Scope (for now)

- Client-side (browser) tracing — separate follow-up phase in hoist-react.
- Trace-based alerting — use Datadog/Grafana for this.
- Logback appender pattern changes to display traceId/spanId in log output.
- Auto-instrumentation agent — using manual/programmatic instrumentation for control.
