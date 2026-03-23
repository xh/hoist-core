# Hoist Core v37 Upgrade Notes

> **From:** v36.x -> v37.0.0 | **Released:** 2026-03-xx | **Difficulty:** 🟢 LOW

## Overview

Hoist Core v37 is a major release paired with hoist-react v83. The headline additions are
OpenTelemetry-based distributed tracing (`TraceService`) with end-to-end client/server support,
an MCP server for AI coding agents, and opt-in metrics publishing via `xhMetricsPublished`.

The only breaking change is the removal of automatic namespace prefixing from `MetricsService`.
Apps that adopted `MetricsService` in v36.3 and relied on automatic prefixing will need to
include the desired prefix in metric names at registration time.

## Upgrade Steps

### 1. Update `gradle.properties`

Bump the hoist-core version.

**File:** `gradle.properties`

Before:
```properties
hoistCoreVersion=36.3.1
```

After:
```properties
hoistCoreVersion=37.0.0
```

### 2. Verify hoist-react Pairing

Hoist Core v37 is a paired release with hoist-react v83. Ensure your app is running
`@xh/hoist >= 83.0` — tracing and metrics publishing features require client-side updates.
See the [hoist-react v83 upgrade notes](https://github.com/xh/hoist-react/blob/develop/docs/upgrade-notes/v83-upgrade-notes.md)
for client-side upgrade steps.

### 3. Update `MetricsService` Metric Names (if applicable)

If your app adopted `MetricsService` in v36.3 and relied on the automatic namespace prefix
(configured via the `namespace` key in `xhMetricsConfig`), you will need to update your metric
registration calls to include the desired prefix directly in the metric name.

**Find affected files:**
```bash
grep -rn "metricsService\.\|MetricsService\." grails-app/ src/ | grep -v "test/"
```

Before (v36.3 -- namespace auto-prefixed):
```groovy
// xhMetricsConfig included: namespace: 'myapp'
// Metric registered as 'orders_processed', auto-prefixed to 'myapp.orders_processed'
metricsService.counter('orders_processed').increment()
```

After (v37 -- explicit name):
```groovy
// Include the prefix directly in the metric name
metricsService.counter('myapp.orders_processed').increment()
```

Also remove the `namespace` key from your `xhMetricsConfig` soft config if present -- it is no
longer used.

### 4. Configure `xhMetricsPublished` (if using metrics export)

Metrics export is now gated by the `xhMetricsPublished` config -- a list of metric name patterns
to include in Prometheus/OTLP export sinks. An empty list (the default) means nothing is
exported, even if export registries are configured.

If your app was exporting all metrics in v36.3, add the desired metric names to this config to
restore export. You can manage this via the Admin Console Servers > Metrics tab (requires
`hoist-react >= 83.0`).

### 5. Configure Tracing (optional)

OpenTelemetry-based distributed tracing is available but requires configuration to enable.
Set up the `xhTraceConfig` soft config to point to your OTLP collector endpoint. See
[`docs/tracing.md`](../tracing.md) for configuration options and examples.

Key features available once configured:

- `TraceService.withSpan()` and `createSpan()` for instrumenting business logic
- `BaseService.observe()` for composable tracing + logging + metrics
- Automatic request spans for all Grails controller actions
- Client span relay via `ClientTraceService` -- browser spans exported through the server pipeline
- Automatic trace context propagation across `task {}` thread boundaries
- `traceId` correlation in log output for ERROR-level and above

### 6. Migrate `@Access` Annotations (if not done in v36)

If you have not yet migrated from the deprecated `@Access` annotation, do so now. `@Access` was
deprecated in v36 in favor of `@AccessRequiresRole`, `@AccessRequiresAllRoles`, and
`@AccessRequiresAnyRole`. See the
[v36 upgrade notes](./v36-upgrade-notes.md#2-migrate-access-annotations) for detailed
before/after examples.

**Find affected files:**
```bash
grep -rn "@Access(" grails-app/controllers/ src/
```

## Verification Checklist

After completing all steps:

- [ ] `./gradlew compileGroovy` succeeds
- [ ] Application starts without errors
- [ ] Admin Console loads and is functional
- [ ] Admin Console Servers > Metrics tab loads (with hoist-react >= 83.0)
- [ ] Metrics export functions correctly (if configured)
- [ ] No `namespace` key remains in `xhMetricsConfig`
- [ ] No deprecated `@Access` annotations remain:
  `grep -rn "@Access(" grails-app/controllers/ src/`

## Reference

- [Toolbox on GitHub](https://github.com/xh/toolbox) -- canonical example of a Hoist app
- [Tracing documentation](../tracing.md) -- configuration and usage guide for OTEL tracing
- [Metrics documentation](../metrics.md) -- MetricsService configuration and usage guide
