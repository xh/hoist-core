> **Status: DRAFT** — This document is awaiting review. Content may be incomplete or subject to
> change. Do not remove this banner until the document has been interactively reviewed and approved.

# Soft Configuration

## Overview

Hoist provides a database-backed soft configuration system that allows runtime-adjustable settings
without code changes or redeployment. Configuration values are stored as `AppConfig` domain objects
with explicit types, managed via the Hoist Admin Console, and accessed through `ConfigService`'s
typed getter API.

Key capabilities:
- **Typed values** — `string`, `int`, `long`, `double`, `bool`, `json`, `pwd`
- **Client visibility** — Configs can be flagged for inclusion in the client-side config payload
- **Password encryption** — The `pwd` type stores values encrypted at rest
- **Instance config overrides** — Environment-specific values can override database entries
- **Change events** — Config changes publish `xhConfigChanged` for reactive cache clearing
- **Environment diffing** — Configs can be compared and synced across environments

## Source Files

| File | Location | Role |
|------|----------|------|
| `AppConfig` | `grails-app/domain/io/xh/hoist/config/` | GORM domain — database-backed config entries |
| `ConfigService` | `grails-app/services/io/xh/hoist/config/` | Primary service — typed getters, event publishing |
| `ConfigDiffService` | `grails-app/services/io/xh/hoist/config/` | Cross-environment config synchronization |

## Key Classes

### AppConfig

A GORM domain class representing a single configuration entry. Stored in the `xh_config` table.

| Property | Type | Description |
|----------|------|-------------|
| `name` | `String` | Unique config name (max 50 chars) |
| `value` | `String` | Stored value (TEXT column for large content) |
| `valueType` | `String` | One of: `string`, `int`, `long`, `double`, `bool`, `json`, `pwd` |
| `clientVisible` | `boolean` | Include in client-side config payload |
| `groupName` | `String` | Logical grouping for Admin Console display (default: `'Default'`) |
| `note` | `String` | Optional description (max 1200 chars) |
| `lastUpdated` | `Date` | Timestamp of last change |
| `lastUpdatedBy` | `String` | Username of last modifier |

#### Value Types

| Type | Stored As | Parsed To | Notes |
|------|-----------|-----------|-------|
| `string` | Plain text | `String` | |
| `int` | Integer string | `Integer` | Validated on save |
| `long` | Long string | `Long` | Validated on save |
| `double` | Double string | `Double` | Validated on save |
| `bool` | `"true"` or `"false"` | `Boolean` | Case-sensitive string |
| `json` | JSON string | `Map` or `List` | Validated as parseable JSON |
| `pwd` | Encrypted string | `String` (decrypted) | Encrypted at rest via Jasypt |

#### Change Events

When an `AppConfig` value is updated, the domain's `beforeUpdate()` lifecycle hook fires a
cluster-wide `xhConfigChanged` event:

```groovy
// Event payload
[key: 'configName', value: parsedValue]
```

This event is published to a Hazelcast topic, so all cluster instances receive it. Services can
react to config changes by subscribing to this topic (or using the `clearCachesConfigs` shortcut
on `BaseService`).

#### Instance Config Overrides

Any `AppConfig` value can be overridden via instance configuration (environment variables, YAML
files, or the instance config directory) without changing the database. The override takes
precedence when the config value is read:

```yaml
# In instance config
myApiEndpoint: https://staging-api.example.com
```

Override values are not encrypted, even for `pwd` type configs — they are treated as plaintext.
If an override value cannot be parsed to the declared type, it is silently ignored (logged at TRACE).

### ConfigService

The primary service for reading and managing configuration values.

#### Typed Getters

```groovy
String region    = configService.getString('myAppRegion')
int maxRetries   = configService.getInt('maxRetries')
long timeout     = configService.getLong('requestTimeoutMs')
double rate      = configService.getDouble('exchangeRate')
boolean enabled  = configService.getBool('featureEnabled')
Map settings     = configService.getMap('dashboardSettings')
List items       = configService.getList('allowedOrigins')
String password  = configService.getPwd('apiSecret')          // returns decrypted
```

All getters accept an optional `notFoundValue` parameter. If the config doesn't exist and no
`notFoundValue` is provided, a `RuntimeException` is thrown. A `RuntimeException` is also thrown if
the requested type doesn't match the config's `valueType`.

#### `getStringList(configName)`

Parses a config value into a list of trimmed strings. Supports a special `[configName]` syntax for
referencing other configs:

```groovy
// Config 'allowedRoles' = "ADMIN, TRADER, [extraRoles]"
// Config 'extraRoles' = "VIEWER, AUDITOR"
List<String> roles = configService.getStringList('allowedRoles')
// → ['ADMIN', 'TRADER', 'VIEWER', 'AUDITOR']
```

#### `setValue(name, value)`

Updates an existing config's value. Automatically serializes objects to JSON for `json` type configs.
Triggers the `xhConfigChanged` event.

```groovy
configService.setValue('maxRetries', 5)
configService.setValue('dashboardSettings', [theme: 'dark', layout: 'grid'])
```

#### `getClientConfig()`

Returns a map of all `clientVisible` configs for the hoist-react client. Passwords are automatically
obscured as `'*********'`, and JSON values are parsed to objects.

#### `ensureRequiredConfigsCreated(reqConfigs)`

Called during application bootstrap to declare configs the application depends on. Creates missing
configs with default values; logs errors for type mismatches on existing configs (but does not
auto-fix them).

```groovy
class BootStrap {
    def configService

    def init = {
        configService.ensureRequiredConfigsCreated([
            'apiEndpoint': [
                valueType: 'string',
                defaultValue: 'https://api.example.com',
                clientVisible: true,
                groupName: 'API',
                note: 'External API base URL'
            ],
            'maxConnections': [
                valueType: 'int',
                defaultValue: '100',
                groupName: 'Performance'
            ],
            'apiSecret': [
                valueType: 'pwd',
                defaultValue: 'changeme',
                groupName: 'API',
                note: 'API authentication secret'
            ]
        ])
    }
}
```

#### `hasConfig(name)`

Checks whether a config with the given name exists. Useful for conditional logic around optional
configs.

### ConfigDiffService

Handles cross-environment config synchronization. The `applyRemoteValues()` method accepts a list
of records from a remote environment and creates, updates, or deletes local configs to match:

- If a config doesn't exist locally, it is created with the remote values.
- If a config exists and remote values are provided, it is updated.
- If a config exists but the remote value is `null`, it is deleted.

This powers the Admin Console's "Config Differ" tool for comparing and syncing configs across
development, staging, and production environments.

## Configuration

Hoist's own framework configs are prefixed with `xh`. Applications should choose a distinct prefix
(e.g., the app name) to avoid collisions.

| Config | Type | Description |
|--------|------|-------------|
| Various `xh*` configs | Mixed | Framework configs created by hoist-core services |
| App-specific configs | Mixed | Created via `ensureRequiredConfigsCreated()` |

## Common Patterns

### Reactive Config Usage

Subscribe to config changes to automatically refresh dependent state:

```groovy
class PricingService extends BaseService {

    // Automatic: clear caches when this config changes
    static clearCachesConfigs = ['pricingConfig']

    // Manual: subscribe for custom handling
    void init() {
        subscribeToTopic(
            topic: 'xhConfigChanged',
            onMessage: { Map msg ->
                if (msg.key == 'pricingConfig') refreshPricingEngine()
            }
        )
    }
}
```

### Timer Intervals from Config

`BaseService.createTimer()` accepts a config name as the `interval` parameter. The timer
automatically adjusts when the config changes:

```groovy
createTimer(
    name: 'refresh',
    runFn: this.&refresh,
    interval: 'xhMyServiceRefreshSecs',   // reads interval from this config
    primaryOnly: true
)
```

### JSON Configs for Complex Settings

Use `json` type configs for structured settings that would be unwieldy as multiple scalar configs:

```groovy
Map trackingConfig = configService.getMap('xhActivityTrackingConfig')
int maxRows = trackingConfig.maxRows ?: 50000
boolean enabled = trackingConfig.enabled ?: true
```

## Client Integration

Configs with `clientVisible: true` are automatically included in the client config payload fetched
by hoist-react during `XH.initAsync()`. The client accesses them via `XH.getConf()`:

```javascript
// Client-side (hoist-react)
const endpoint = XH.getConf('apiEndpoint');
const settings = XH.getConf('dashboardSettings');  // parsed JSON object
```

Password configs are always obscured when sent to the client, even if marked `clientVisible`.

## Common Pitfalls

### Accessing raw value without typed getter

Always use the typed getters on `ConfigService` rather than querying `AppConfig` domain objects
directly:

```groovy
// ✅ Do: Use typed getter
String region = configService.getString('myAppRegion')

// ❌ Don't: Query domain directly
def region = AppConfig.findByName('myAppRegion').value
```

The typed getters handle type coercion, password decryption, instance config overrides, and caching.

### Not declaring required configs

If application code depends on a config that doesn't exist, the typed getter will throw a
`RuntimeException`. Always declare required configs in `ensureRequiredConfigsCreated()` during
bootstrap to ensure they exist with sensible defaults.

### Modifying `pwd` configs via direct domain access

Password configs must go through `AppConfig`'s lifecycle hooks for encryption. Setting
`appConfig.value = 'newSecret'` and saving will encrypt the value. But bypassing the domain (e.g.,
raw SQL) will store plaintext, breaking decryption.

### Ignoring instance config overrides

When troubleshooting config issues, remember to check for instance config overrides. A config may
have one value in the database but a different effective value from an environment variable or YAML
file. The Admin Console shows override values when present.
