# Hoist Core v35 Upgrade Notes

> **From:** v34.x -> v35.0.0 | **Released:** 2026-01-05 | **Difficulty:** 🟢 LOW

## Overview

Hoist Core v35 is a minor-impact release that introduces a generic key type for `CacheEntry`, adds
client app tracking support, and upgrades several dependencies. The Grails version receives a
bugfix-level bump from 7.0.2 to 7.0.4.

The most significant app-level impacts are:

- **CacheEntry generic type change** -- apps explicitly declaring `CacheEntry<T>` must add the key
  type parameter (rare in practice).
- **Database schema change** -- a new `client_app_code` column and index are required on
  `xh_track_log`.
- **Apache POI major upgrade** -- POI moves from 4.x to 5.x. No code changes required, but Excel
  export functionality should be tested.

## Prerequisites

Before starting, ensure:

- [ ] You have database access to run ALTER TABLE / CREATE INDEX statements (if GORM auto-schema
  updates are not enabled for your environment)

## Upgrade Steps

### 1. Update `gradle.properties`

Bump the hoist-core version.

**File:** `gradle.properties`

Before:
```properties
hoistCoreVersion=34.x.x
```

After:
```properties
hoistCoreVersion=35.0.0
```

We also recommend updating your `grailsVersion` to `7.0.4` to stay aligned with hoist-core's
tested version:

```properties
grailsVersion=7.0.4
```

No other property changes are required.

### 2. Add `client_app_code` column to `xh_track_log`

A new `clientAppCode` field was added to `TrackLog` to support disambiguation of tracking data for
apps with multiple client apps. This requires a schema change.

If your application's GORM configuration includes `dbCreate = "update"` (or equivalent) and the
app's database service account has DDL/schema management privileges, Grails/GORM will
automatically add the new column on startup. In that case, no manual SQL is required — verify the
column was created after the first startup on the new version.

For environments where auto-schema updates are disabled or the service account lacks DDL
privileges, run the following SQL manually (adjusted as needed for your database):

```sql
ALTER TABLE xh_track_log ADD COLUMN client_app_code VARCHAR(50) NULL;
CREATE INDEX idx_xh_track_log_client_app_code ON xh_track_log (client_app_code);
```

Notes:
- The column is nullable — existing rows will have `NULL` values.
- Clients running `hoist-react >= 79` will populate this field automatically. If your client is
  still on an earlier version, the column will remain `NULL` until the client is upgraded — this
  does not cause errors.
- If your database uses a different syntax for nullable columns or index creation, adjust
  accordingly (e.g. MS SQL Server uses `ADD client_app_code NVARCHAR(50) NULL`).

### 3. Update `CacheEntry` type declarations (if applicable)

The generic signature of `CacheEntry` changed from `CacheEntry<T>` (value type only) to
`CacheEntry<K, T>` (key type + value type), allowing non-string cache keys.

**Find affected files:**
```bash
grep -r "CacheEntry<" grails-app/ src/
```

If you have explicit `CacheEntry` type declarations, update them:

Before:
```groovy
CacheEntry<MyValue> entry = cache.getEntry(key)
```

After:
```groovy
CacheEntry<String, MyValue> entry = cache.getEntry(key)
```

Most applications do not reference `CacheEntry` directly — they use `Cache<K, V>` and work with
values rather than entries. If `grep` returns no results, no changes are needed.

Note: `CacheEntryChanged` (used in `onChange` callbacks) is **not** affected by this change.

### 4. Test Excel export functionality

Apache POI was upgraded from `4.1.2` to `5.5.1` (a major version bump). Hoist Core handles this
internally, so no code changes are required. However, if your app relies on Excel export features,
verify that exports produce correct output after upgrading.

## Verification Checklist

After completing all steps:

- [ ] `./gradlew compileGroovy` succeeds
- [ ] Application starts without errors
- [ ] Admin Console loads and is functional
- [ ] Authentication works (login/logout)
- [ ] Activity tracking records appear in the Admin Console (verify `clientAppCode` column exists)
- [ ] Excel exports produce valid output (if applicable)
- [ ] No outdated `CacheEntry<T>` patterns remain: `grep -r "CacheEntry<" grails-app/ src/`

## Reference

- [Toolbox on GitHub](https://github.com/xh/toolbox) -- canonical example of a Hoist app
