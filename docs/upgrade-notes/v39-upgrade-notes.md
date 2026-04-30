# Hoist Core v39 Upgrade Notes

> **From:** v38.x → v39.0.0 | **Released:** 2026-04-30 | **Difficulty:** 🟢 LOW

## Overview

Hoist Core v39 brings a major expansion of the typed soft-config system, broader OpenTelemetry
coverage on the server (server-load spans, JDBC auto-instrumentation, OTLP suppression in local
dev), and ergonomic cleanup across the tracing APIs. Headline framework changes:

- **Typed `ConfigSpec` / `PreferenceSpec` / `RoleSpec`** replace the prior untyped `Map`-based
  arguments to `ensureRequiredConfigsCreated()`, `ensureRequiredPrefsCreated()`, and
  `ensureRequiredRolesCreated()`. The `Map` form is deprecated, still works for v39, and is
  scheduled for removal in v42. Migrating now is **strongly recommended** — it is purely
  mechanical and unlocks IDE autocomplete and compile-time validation.
- **Optional typed-config opt-in** — apps can now declare `TypedConfigMap` subclasses for their
  own JSON-valued soft configs to centralize shape and defaults on the server. This is opt-in,
  per-config, and recommended for configs with a stable known key set. Not required.
- **Telemetry package restructuring** — many `io.xh.hoist.telemetry.*` classes moved into
  `telemetry.metric` / `telemetry.trace` subpackages. Compiler-caught and IDE-fixable.
- **`alwaysSampleErrors` removed from `xhTraceConfig`** — head-based sampling rules now decide
  on all spans, including errors. If your app's database has the key set on `xhTraceConfig`,
  the typed-config system will log a `WARN` at startup flagging it as unknown — review and
  remove via the Admin Console at your convenience.

There are no database schema changes in this release.

## Prerequisites

Before starting, ensure:

- [ ] Running hoist-core v38.x (no special intermediate version needed)
- [ ] **JDK 25** available locally if you build on the toolchain (published JAR still targets
      Java 17 bytecode — apps on JDK 17+ runtimes need no change)
- [ ] **hoist-react compatibility (informational only)** — v39 imposes no new minimum on
      hoist-react. Apps on hoist-react v84.x or v85.x can adopt hoist-core v39 in either
      order. hoist-react v85 is the natural pairing — both releases share the
      `alwaysSampleErrors` removal and name-based `sampleRules` matching — but it is not a
      hard requirement.

## Upgrade Steps

### 1. Update `gradle.properties`

Bump the hoist-core version.

**File:** `gradle.properties`

Before:
```properties
hoistCoreVersion=38.0.0
```

After:
```properties
hoistCoreVersion=39.0.0
```

Then run `./gradlew assemble` (or your app's equivalent) to pull the new dependency.

### 2. Resolve any compiler errors from the telemetry package move

The `io.xh.hoist.telemetry.*` package was reorganized into `telemetry.metric` and
`telemetry.trace` subpackages, with a couple of class renames (most notably
`HoistSampler` → `ManualRateSampler`, and `AccessInterceptor` → `HoistInterceptor`).

Most apps don't import these classes directly — they use them via injected services or static
helpers like `Utils.traceService`. If your app does extend or import any of them, the compiler
will flag the broken imports and your IDE's "fix imports" action will resolve them. Don't
hand-write these — let the compiler tell you what moved.

If `./gradlew compileGroovy` is clean, you have nothing to do for this step.

### 3. Migrate `ensureRequiredXxxCreated()` from `Map` to typed `Spec` form

> **Strongly recommended.** This is the highest-value mechanical change in the v39 upgrade.

The `Map`-based forms of `ensureRequiredConfigsCreated()`, `ensureRequiredPrefsCreated()`, and
`ensureRequiredRolesCreated()` are deprecated and emit a startup `WARN`. They will be removed
in v42. Migrating now is straightforward and gets you compile-time validation.

**Before** — typically in `BootStrap.groovy`:

```groovy
configService.ensureRequiredConfigsCreated([
    pricingApiUrl: [
        valueType: 'string',
        defaultValue: 'https://prices.example.com',
        clientVisible: false,
        groupName: 'Pricing',
        note: 'Upstream pricing endpoint.'
    ],
    pricingFeatureFlags: [
        valueType: 'json',
        defaultValue: [showAdvanced: false],
        clientVisible: true,
        groupName: 'Pricing'
    ]
])

prefService.ensureRequiredPrefsCreated([
    showRetiredFunds: [
        type: 'bool',
        defaultValue: false,
        groupName: 'Funds',
        note: 'When true, include retired funds in the master grid.'
    ]
])

roleService.ensureRequiredRolesCreated([
    PRICING_ADMIN: [
        category: 'Pricing',
        notes: 'Can edit pricing configs and overrides.'
    ]
])
```

**After:**

```groovy
import io.xh.hoist.config.ConfigSpec
import io.xh.hoist.pref.PreferenceSpec
import io.xh.hoist.role.RoleSpec

configService.ensureRequiredConfigsCreated([
    new ConfigSpec(
        name: 'pricingApiUrl',
        valueType: 'string',
        defaultValue: 'https://prices.example.com',
        clientVisible: false,
        groupName: 'Pricing',
        note: 'Upstream pricing endpoint.'
    ),
    new ConfigSpec(
        name: 'pricingFeatureFlags',
        valueType: 'json',
        defaultValue: [showAdvanced: false],
        clientVisible: true,
        groupName: 'Pricing'
    )
])

prefService.ensureRequiredPrefsCreated([
    new PreferenceSpec(
        name: 'showRetiredFunds',
        type: 'bool',
        defaultValue: false,
        groupName: 'Funds',
        notes: 'When true, include retired funds in the master grid.'
    )
])

roleService.ensureRequiredRolesCreated([
    new RoleSpec(
        name: 'PRICING_ADMIN',
        category: 'Pricing',
        notes: 'Can edit pricing configs and overrides.'
    )
])
```

The translation is mechanical:

- The map key (config / pref / role name) becomes `name:` on the typed Spec.
- All other fields keep the same names — except `PreferenceSpec`, which uses `notes:` (plural)
  instead of the legacy `note:`. The deprecated overload silently translates this for you, but
  the typed form requires `notes:`.
- Imports: add `io.xh.hoist.config.ConfigSpec`, `io.xh.hoist.pref.PreferenceSpec`,
  `io.xh.hoist.role.RoleSpec` as needed.

> **For agents performing this upgrade:** This is a safe automated change. Find the
> `ensureRequiredConfigsCreated`, `ensureRequiredPrefsCreated`, and `ensureRequiredRolesCreated`
> call sites (typically a single `BootStrap.groovy` in the app, occasionally an additional
> service-level call), apply the translation above, and confirm compilation succeeds.

### 4. (Optional) Adopt `TypedConfigMap` for app JSON configs

> **Conditional opt-in — discuss with the developer before applying.** Skip this step entirely
> if the app has no JSON-valued soft configs with a stable known key set, or if the developer
> declines.

Hoist Core v39 ships a typed-config system that lets you declare a `TypedConfigMap` subclass for
a JSON config. The class becomes the single source of truth for the config's shape and
defaults on **both server and client**: server reads via `configService.getObject(Class)` are
typed and default-populated; clients receive the same default-populated payload via
`getClientConfig()` without duplicating defaults in TypeScript.

This is **fully optional and per-config**. The system is designed for JSON configs with a
stable, known key set (e.g. an integration's connection settings). It is **not recommended**
for free-form key/value maps (e.g. feature-flag bags keyed by arbitrary strings), list-valued
configs, or configs whose shape changes frequently — continue to use `configService.getMap` /
`getList` for those.

**For agents performing this upgrade:**

1. Inspect the app's `BootStrap.groovy` (or wherever `ensureRequiredConfigsCreated` is called).
   Identify any entries with `valueType: 'json'` whose `defaultValue` is a map with a stable,
   well-defined key set (rather than an arbitrary bag).
2. **Pause and check with the developer** before proceeding. Surface the candidate configs and
   ask whether they'd like to adopt `TypedConfigMap` for any of them. Some apps will want to;
   others will prefer to skip this entirely. Either is fine.
3. For each config the developer opts in to, create a `TypedConfigMap` subclass mirroring the
   default-value shape, register it via `typedClass:` on the `ConfigSpec`, and migrate any
   server-side `configService.getMap(name)` reads to `configService.getObject(MyConfig)`.

See [`docs/configuration.md`](../configuration.md#typed-configs-via-typedconfigmap) for the full
guide, including nested-class patterns, list-of-typed-map support, and the
`init(args)`-in-constructor convention.

### 5. Recompile and verify

```bash
./gradlew compileGroovy
```

If anything fails, the most likely cause is a missed import from step 2 or a stray `note:` →
`notes:` rename in a `PreferenceSpec`. Both are clear compiler diagnostics.

## Verification Checklist

After completing all steps:

- [ ] `./gradlew compileGroovy` succeeds
- [ ] Application starts without errors
- [ ] No `ensureRequiredXxxCreated(Map) is deprecated` warnings in startup logs (confirms the
      Spec migration is complete)
- [ ] Admin Console > Configs / Preferences / Roles all load and round-trip edits
- [ ] If you opted into `TypedConfigMap` for any app config: edit it via the Admin Console and
      confirm reads/writes both succeed (server validates that admin-saved values can populate
      the typed class)
- [ ] If you use distributed tracing: spans still flow to your collector, and any
      `sampleRules` you rely on still match as expected
- [ ] Authentication works (login/logout)

## Reference

- [Configuration guide — Typed configs via `TypedConfigMap`](../configuration.md#typed-configs-via-typedconfigmap)
- [Tracing guide](../tracing.md)
- [Toolbox on GitHub](https://github.com/xh/toolbox) — canonical example of a Hoist app, now
  migrated to the typed Spec APIs
