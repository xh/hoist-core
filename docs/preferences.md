> **Status: DRAFT** — This document is awaiting review. Content may be incomplete or subject to
> change. Do not remove this banner until the document has been interactively reviewed and approved.

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

## Key Classes

### Preference

A GORM domain class representing a preference definition. Stored in the `xh_preference` table.

| Property | Type | Description |
|----------|------|-------------|
| `name` | `String` | Unique preference name (max 50 chars) |
| `type` | `String` | One of: `string`, `int`, `long`, `double`, `bool`, `json` |
| `defaultValue` | `String` | Default value (TEXT column) |
| `groupName` | `String` | Logical grouping for Admin Console (default: `'Default'`) |
| `notes` | `String` | Optional description (max 1200 chars) |
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
String theme        = prefService.getString('theme')
int pageSize        = prefService.getInt('defaultPageSize')
boolean showTips    = prefService.getBool('showTooltips')
Map gridLayout      = prefService.getMap('positionGridLayout')
List favorites      = prefService.getList('favoriteReports')

// Read another user's preference
String otherTheme   = prefService.getString('theme', 'jane.doe')
```

If the user has a `UserPreference` record, it returns the custom value. Otherwise, it returns the
`Preference` default value.

A `RuntimeException` is thrown if the preference doesn't exist or if the requested type doesn't
match the preference's type.

#### Typed Setters

```groovy
prefService.setString('theme', 'dark')
prefService.setInt('defaultPageSize', 50)
prefService.setBool('showTooltips', false)
prefService.setMap('positionGridLayout', [columns: [...], sort: [...]])
prefService.setList('favoriteReports', ['daily-pnl', 'risk-summary'])

// Set another user's preference
prefService.setString('theme', 'dark', 'jane.doe')
```

If the new value equals the default value, the `UserPreference` record is **deleted** rather than
stored — keeping the database clean and ensuring that future changes to the default will apply.

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

#### `getLimitedClientConfig(keys)`

Returns a subset of preferences by name — more efficient when only specific preferences are needed.

#### `ensureRequiredPrefsCreated(requiredPrefs)`

Called during application bootstrap to declare preferences the application depends on. Creates
missing preferences with default values; logs errors for type mismatches on existing preferences.

```groovy
class BootStrap {
    def prefService

    def init = {
        prefService.ensureRequiredPrefsCreated([
            'theme': [
                type: 'string',
                defaultValue: 'light',
                groupName: 'UI',
                note: 'User interface theme'
            ],
            'defaultPageSize': [
                type: 'int',
                defaultValue: '25',
                groupName: 'UI'
            ],
            'dashboardLayout': [
                type: 'json',
                defaultValue: '{"panels": []}',
                groupName: 'Dashboard'
            ]
        ])
    }
}
```

### PrefDiffService

Handles cross-environment preference synchronization (preference *definitions*, not user values).
Works identically to `ConfigDiffService` — creates, updates, or deletes local preference
definitions to match a remote environment.

## Common Patterns

### UI Layout Preferences

A common pattern stores grid column configurations, dashboard layouts, or panel states as `json`
preferences:

```groovy
// Server-side service reading a user's saved layout
Map getLayout(String username) {
    prefService.getMap('dashboardLayout', username)
}

void saveLayout(Map layout) {
    prefService.setMap('dashboardLayout', layout)
}
```

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
const theme = XH.prefService.get('theme');
XH.prefService.set('theme', 'dark');  // persists to server
```

The hoist-react `PrefService` manages the client-local state and syncs changes back to the server
via `/xh/setPreference` endpoints.

Preferences with a `local` flag are handled entirely in the browser (stored in `localStorage`)
and are never sent to or read from the server. This is useful for preferences that are
device-specific (e.g., window size, panel collapsed state) and don't need to roam across devices.

## Common Pitfalls

### Confusing preferences with configuration

Preferences are per-user settings; configs are global application settings. Use
[soft configuration](./configuration.md) for values that apply to all users (API endpoints,
feature flags, thresholds). Use preferences for values that should differ per user (theme,
layout, page size).

### Storing large data in preferences

While the `userValue` column is TEXT, very large JSON preferences (e.g., serialized view state
with thousands of entries) can impact performance. Consider using
[JsonBlobs](./jsonblob.md) for large, named JSON documents.

### Not declaring required preferences

Like configs, preferences that don't exist will throw a `RuntimeException` when accessed. Always
declare required preferences in `ensureRequiredPrefsCreated()` during bootstrap.

### Forgetting deletion cascades

Deleting a `Preference` definition cascades to delete all related `UserPreference` records. This
is usually desired but be aware that removing a preference from `ensureRequiredPrefsCreated()` and
manually deleting it will lose all users' customizations for that preference.
