# Hoist Core v38 Upgrade Notes

> **From:** v37.x -> v38.0.0 | **Released:** 2026-04-15 | **Difficulty:** 🟢 LOW

## Overview

Hoist Core v38 adds rule-based span sampling, per-logger stacktrace and start-message controls,
customizable OTEL resource attributes, and aligns telemetry tag names with OTEL semantic
conventions.

The only breaking change is a database schema addition:

- **Two new nullable columns on `xh_log_level`** -- `suppress_stack_trace` and
  `include_start_messages`. Apps with `dbCreate: update` will have these added automatically.

## Prerequisites

Before starting, ensure:

- [ ] **hoist-core dependency** updated to `38.0.0` in `build.gradle`
- [ ] **Database access** if your environment does not use `dbCreate: update` (manual DDL required)

## Upgrade Steps

### 1. Update `build.gradle`

Bump the hoist-core dependency version.

**File:** `build.gradle`

Before:
```groovy
implementation "io.xh:hoist-core:37.0.2"
```

After:
```groovy
implementation "io.xh:hoist-core:38.0.0"
```

### 2. Database Schema Update

Two new nullable `Boolean` columns must be added to the `xh_log_level` table. These support the
new per-logger stacktrace suppression and start-message controls.

**Apps with `dbCreate: update`** will have these columns added automatically on startup — no
action required.

**For manually managed schemas**, review and run the following SQL, modified as needed for your
database:

```sql
ALTER TABLE xh_log_level ADD suppress_stack_trace BIT NULL;
ALTER TABLE xh_log_level ADD include_start_messages BIT NULL;
```

> **Note:** Column types may vary by database. The SQL above uses `BIT NULL` (SQL Server). For
> MySQL use `TINYINT(1) NULL`, for PostgreSQL use `BOOLEAN NULL`.

## Verification Checklist

After completing all steps:

- [ ] `./gradlew compileGroovy` succeeds
- [ ] Application starts without errors
- [ ] Admin Console loads and is functional
- [ ] Admin Console > Log Levels tab shows the new `Suppress Stack Trace` and `Include Start
  Messages` columns
- [ ] Authentication works (login/logout)

## Reference

- [Toolbox on GitHub](https://github.com/xh/toolbox) -- canonical example of a Hoist app
