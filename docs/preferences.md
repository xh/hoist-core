# Preferences

## Overview

Hoist's preference system provides per-user settings with administrator-defined defaults.
Preferences are two-tiered: `Preference` domain objects define the available settings with their
types and default values, while `UserPreference` domain objects store individual users' customized
values. When a user hasn't set a preference, they get the default.

This system powers user-specific UI settings (grid column layouts, theme choices, dashboard
configurations) while giving administrators control over the available preferences and their
defaults via the Admin Console.

Preferences are distinct from [soft configuration](./configuration.md) — configs are global
application settings, while preferences are per-user.

## Source Files

| File | Location | Role |
|------|----------|------|
| `Preference` | `grails-app/domain/io/xh/hoist/pref/` | GORM domain — preference definitions with defaults |
| `UserPreference` | `grails-app/domain/io/xh/hoist/pref/` | GORM domain — per-user preference values |
| `PrefService` | `grails-app/services/io/xh/hoist/pref/` | Primary service — typed getters/setters |
| `PrefDiffService` | `grails-app/services/io/xh/hoist/pref/` | Cross-environment preference synchronization |
| `PreferenceSpec` | `src/main/groovy/io/xh/hoist/pref/` | Typed specification for required preference definitions |
| `PreferenceAdminController` | `grails-app/controllers/io/xh/hoist/admin/` | Admin Console CRUD endpoints for preference definitions |
| `XhController` | `grails-app/controllers/io/xh/hoist/impl/` | Client-facing `getPrefs` / `setPrefs` endpoints |

## Key Classes

### Preference

A GORM domain class representing a preference definition. Stored in the `xh_preference` table.

| Property | Type | Description |
|----------|------|-------------|
| `name` | `String` | Unique preference name (max 50 chars) |
| `type` | `String` | One of: `string`, `int`, `long`, `double`, `bool`, `json` |
| `defaultValue` | `String` | Default value (TEXT column) |
| `groupName` | `String` | Logical grouping for Admin Console (default: `'Default'`) |
| `notes` | `String` | Optional description (max 1200 chars, nullable) |
| `lastUpdated` | `Date` | Timestamp of last change |
| `lastUpdatedBy` | `String` | Username of last modifier |

Note that preferences support the same value types as `AppConfig` except `pwd` — there is no need
for encrypted per-user preferences.

### UserPreference

A GORM domain class storing a user's custom value for a preference. Stored in the
`xh_user_preference` table. Only created when a user explicitly sets a value different from the
default.

| Property | Type | Description |
|----------|------|-------------|
| `username` | `String` | User who owns this preference value |
| `userValue` | `String` | The user's custom value (TEXT column) |
| `preference` | `Preference` | Reference to the preference definition |
| `lastUpdated` | `Date` | Timestamp of last change |
| `lastUpdatedBy` | `String` | Username of last modifier |

Unique constraint on `(username, preference)` — each user can have at most one value per
preference. The `username` column is indexed for efficient per-user lookups.

### PrefService

The primary service for reading and writing user preferences.

#### Typed Getters

All getters accept an optional `username` parameter (defaults to the current user):

```groovy
String theme = prefService.getString('theme')
int pageSize = prefService.getInt('defaultPageSize')
long bigCount = prefService.getLong('bigCount')
double rate = prefService.getDouble('exchangeRate')
boolean useLivePrices = prefService.getBool('useLivePrices')
Map apiConfig = prefService.getMap('pricingApiConfig')
List favorites = prefService.getList('favoriteReports')

// Read another user's preference
String otherTheme = prefService.getString('theme', 'jane.doe')
```

If the user has a `UserPreference` record, it returns the custom value. Otherwise, it returns the
`Preference` default value.

A `RuntimeException` is thrown if the preference doesn't exist or if the requested type doesn't
match the preference's type.

#### Typed Setters

```groovy
prefService.setString('theme', 'dark')
prefService.setInt('defaultPageSize', 50)
prefService.setLong('bigCount', 100000L)
prefService.setDouble('exchangeRate', 1.25d)
prefService.setBool('showTooltips', false)
prefService.setMap('positionGridLayout', [columns: [...], sort: [...]])
prefService.setList('favoriteReports', ['daily-pnl', 'risk-summary'])

// Set another user's preference
prefService.setString('theme', 'dark', 'jane.doe')
```

There is also an untyped `setPreference(key, value, username)` that accepts a raw String value
without type validation. This is used internally by `XhController.setPrefs()` for non-Map/non-List
values from the client, but typed setters are preferred for server-side code.

#### `unsetPreference(key, username)`

Removes a user's custom value, reverting to the default:

```groovy
prefService.unsetPreference('theme')
```

#### `clearPreferences(username)`

Removes all custom preference values for a user, reverting everything to defaults:

```groovy
prefService.clearPreferences('jane.doe')
```

#### `isUnset(key, username)`

Checks whether a user is using the default value (no `UserPreference` record exists):

```groovy
if (prefService.isUnset('theme')) {
    // User hasn't customized their theme
}
```

#### `getClientConfig()`

Returns all preferences for the current user in a structure consumed by hoist-react:

```json
{
  "theme": {
    "type": "string",
    "value": "dark",
    "defaultValue": "light"
  },
  "defaultPageSize": {
    "type": "int",
    "value": 25,
    "defaultValue": 25
  }
}
```

The `value` field contains the user's custom value if set, otherwise the default. JSON values are
parsed to objects.

#### `ensureRequiredPrefsCreated(requiredPrefs)`

Applications should register all preferences they intend to use via this method in their
`BootStrap.groovy`. Accepts a `List<PreferenceSpec>` where each `PreferenceSpec` specifies the
preference's `name`, `type`, `defaultValue`, and optional fields (`groupName`, `notes`). A
preference must exist as a `Preference` record in the database before it can be read or written —
calls to `PrefService` for a non-existent preference will throw a `RuntimeException`. This method
creates any missing preferences with the supplied defaults and logs errors if an existing
preference has a mismatched type.

A deprecated overload accepting `Map<String, Map>` (where the outer key is the preference name)
is still supported for backward compatibility but should be migrated to `PreferenceSpec`. Note that
the old Map API used `note` (singular) for consistency with the former `ensureRequiredConfigsCreated()`
Map API; the deprecated overload maps `note` to the correct `notes` (plural) field on `Preference`
automatically.

```groovy
import io.xh.hoist.pref.PreferenceSpec

class BootStrap {
    def prefService

    def init = {
        prefService.ensureRequiredPrefsCreated([
            new PreferenceSpec(
                name: 'theme',
                type: 'string',
                defaultValue: 'light',
                groupName: 'UI',
                notes: 'User interface theme'
            ),
            new PreferenceSpec(
                name: 'defaultPageSize',
                type: 'int',
                defaultValue: '25',
                groupName: 'UI'
            ),
            new PreferenceSpec(
                name: 'dashboardLayout',
                type: 'json',
                defaultValue: [panels: []],
                groupName: 'Dashboard'
            )
        ])
    }
}
```

### PrefDiffService

An internal Hoist service backing the Admin Console's cross-environment diff tool. Synchronizes
preference *definitions* (not user values) by creating, updating, or deleting local preference
definitions to match a remote environment. Not intended for direct use by application code.

### PreferenceAdminController

An internal Hoist controller providing Admin Console CRUD endpoints for managing `Preference`
definitions. Extends `AdminRestController`, which requires `HOIST_ADMIN_READER` for read access
and `HOIST_ADMIN` for create/update/delete operations. Has `trackChanges = true`, so all
definition changes are recorded via activity tracking. Not intended as a public API —
applications interact with preferences via `PrefService`.

## Built-in Preferences

Hoist's own `BootStrap` creates the following `xh`-prefixed preferences. Applications should not
redefine these but may read them via `PrefService`:

| Preference | Type | Default | Description |
|------------|------|---------|-------------|
| `xhAutoRefreshEnabled` | `bool` | `true` | Enables client `AutoRefreshService` (also requires `xhAutoRefreshIntervals` config) |
| `xhIdleDetectionDisabled` | `bool` | `false` | Prevents `IdleService` from suspending the app due to inactivity |
| `xhLastReadChangelog` | `string` | `'0.0.0'` | Most recent changelog version viewed by the user |
| `xhShowVersionBar` | `string` | `'auto'` | Controls version footer display — `'auto'`, `'always'`, or `'never'` |
| `xhSizingMode` | `json` | `{}` | Sizing mode for Grid and responsive components, keyed by platform |
| `xhTheme` | `string` | `'system'` | Visual theme — `'light'`, `'dark'`, or `'system'` |

## Common Patterns

### Preference-driven Service Behavior

Services can read preferences to customize behavior per user:

```groovy
class ReportService extends BaseService {

    List generateReport() {
        int pageSize = prefService.getInt('defaultPageSize')
        String format = prefService.getString('reportFormat')
        // Generate report with user's preferred settings...
    }
}
```

## Client Integration

Preferences are loaded by hoist-react during `XH.initAsync()` and accessible via the `prefService`
on the client:

```javascript
// Client-side (hoist-react)
const theme = XH.getPref('theme');
XH.prefService.set('theme', 'dark');  // persists to server
```

The hoist-react `PrefService` manages the client-local state and syncs changes back to the server
via `XhController` endpoints (`/xh/getPrefs` to load, `/xh/setPrefs` to persist).

In practice, most client-side interaction with preferences happens indirectly through hoist-react's
**persistence system**, which automatically saves and restores UI state — grid column layouts,
active tabs, panel sizes, and more — using preferences as a backing store. See the
[hoist-react persistence documentation](https://github.com/xh/hoist-react/blob/develop/docs/persistence.md)
for details on how `persistWith` connects client components to preferences persisted back here to
the server.

## Common Pitfalls

### Confusing preferences with configuration

Preferences are per-user settings; configs are global application settings. Use
[soft configuration](./configuration.md) for values that apply to all users (API endpoints,
feature flags, thresholds). Use preferences for values that should differ per user (theme,
layout, page size).

### Storing large data in preferences

While the `userValue` column is TEXT, very large JSON preferences (e.g., serialized view state
with thousands of entries) can impact performance. Consider using
JsonBlobs for large, named JSON documents.

### Not declaring required preferences

Like configs, preferences that don't exist will throw a `RuntimeException` when accessed. Always
declare required preferences in `ensureRequiredPrefsCreated()` during bootstrap.

### Forgetting deletion cascades

Deleting a `Preference` definition cascades to delete all related `UserPreference` records. This
is usually desired but be aware that deleting a preference definition will permanently remove all
users' customizations for that preference.
