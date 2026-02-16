> **Status: DRAFT** — This document is awaiting review. Content may be incomplete or subject to
> change. Do not remove this banner until the document has been interactively reviewed and approved.

# Configuration

Hoist applications are configured through two complementary systems:

1. **Instance configuration** — Low-level, deployment-specific settings loaded at startup via
   environment variables. These provide the database connection, environment identity, and other
   values that must be in place before the application can fully initialize.
2. **Soft configuration** — Database-backed `AppConfig` entries that are runtime-adjustable via the
   Admin Console without code changes or redeployment.

Instance configs bootstrap the app; soft configs tune it once it's running.

---

## Instance Configuration

### Overview

Instance configs provide minimal, deployment-specific settings for a particular running instance of
the application. They are typically used for database credentials, the server URL, environment
identity, and other sensitive or environment-specific values that must be kept out of source code.

Instance configs are loaded by `InstanceConfigUtils` at startup and accessed via its static
`getInstanceConfig(key)` method, which returns raw `String` values.

### Source Files

| File | Location | Role |
|------|----------|------|
| `InstanceConfigUtils` | `src/main/groovy/io/xh/hoist/util/` | Loads and serves instance configs |
| `RuntimeConfig` | `src/main/groovy/io/xh/hoist/configuration/` | Applies instance configs to Grails runtime settings |
| `AppEnvironment` | `src/main/groovy/io/xh/hoist/` | Enum of deployment environments |

### Environment Variables (Recommended)

The standard approach is to provide instance configs as environment variables following the naming
convention:

```
APP_[APP_CODE]_[KEY]
```

Where `[APP_CODE]` and `[KEY]` are both in UPPER_SNAKE_CASE. The key is converted from the
camelCase used in code via Jackson's `UpperSnakeCaseStrategy`:

| Code Key | Environment Variable (for app `myApp`) |
|----------|----------------------------------------|
| `dbHost` | `APP_MY_APP_DB_HOST` |
| `dbPassword` | `APP_MY_APP_DB_PASSWORD` |
| `serverURL` | `APP_MY_APP_SERVER_URL` |
| `bootstrapAdminUser` | `APP_MY_APP_BOOTSTRAP_ADMIN_USER` |

In practice, most applications set these via a `.env` file in the project root, loaded by a Gradle
plugin and passed to the application as environment variables. A `.env.template` file checked into
the repo documents the available keys with example values:

```bash
# .env.template — copy to .env and customize for your local environment.
# .env is gitignored and never committed.

# Required
APP_MYAPP_ENVIRONMENT=Development
APP_MYAPP_DB_HOST=localhost
APP_MYAPP_DB_SCHEMA=myapp
APP_MYAPP_DB_USER=myapp
APP_MYAPP_DB_PASSWORD=myapp
APP_MYAPP_SERVER_URL=http://localhost:8080/myapp

# Bootstrap admin — grants HOIST_ADMIN roles in local dev only
#APP_MYAPP_BOOTSTRAP_ADMIN_USER=dev.user
```

### Alternative Sources

Two file-based alternatives are also supported, though environment variables are the standard
approach and should be preferred unless there is a clear reason to use files instead.

**YAML file** — A YAML file containing key-value pairs. The default location is
`/etc/hoist/conf/[appCode].yml`, customizable via the `-Dio.xh.hoist.instanceConfigFile` Java
option:

```yaml
dbHost: localhost
dbSchema: myapp
dbUser: myapp
dbPassword: secret
serverURL: http://localhost:8080/myapp
environment: Development
```

**Config directory** — A directory containing individual files where filenames are config keys and
file contents are values. Intended for Docker/Kubernetes environments with configs or secrets
mounted to a local path. If the directory contains an `[appCode].yml` file, it takes priority over
individual files.

If both an environment variable and a file-based entry exist for the same key, the environment
variable wins.

### Common Instance Config Keys

| Key | Purpose |
|-----|---------|
| `environment` | `AppEnvironment` — e.g., `Development`, `Staging`, `Production` |
| `serverURL` | Base URL for the Grails application (used in `RuntimeConfig.defaultConfig()`) |
| `dbHost` | Database hostname |
| `dbSchema` | Database schema/catalog name |
| `dbUser` | Database username |
| `dbPassword` | Database password |
| `bootstrapAdminUser` | Admin user for local dev (see [authorization.md](./authorization.md)) |
| `multiInstanceEnabled` | Set to `'false'` to disable multi-instance clustering |

### AppEnvironment

The application's `AppEnvironment` is also sourced from instance configuration, with the following
priority:

1. `-Dio.xh.hoist.environment` Java option
2. `APP_[APP_CODE]_ENVIRONMENT` environment variable
3. `environment` key in config file/directory
4. Fallback to `Development` in local development only (throws otherwise)

Available environments: `Production`, `Beta`, `Staging`, `Development`, `Test`, `UAT`, `BCP`.

Note this is distinct from the Grails environment — multiple non-production Hoist environments
(Development, Staging, Beta) may all run in Grails "production" mode on their respective servers.

### Usage in Application Code

Instance configs are primarily consumed in `runtime.groovy` to configure database connections and
other Grails settings:

```groovy
// grails-app/conf/runtime.groovy
import io.xh.hoist.configuration.RuntimeConfig
import static io.xh.hoist.util.InstanceConfigUtils.getInstanceConfig

RuntimeConfig.defaultConfig(this)  // applies serverURL

dataSource {
    url = "jdbc:mysql://${getInstanceConfig('dbHost')}/${getInstanceConfig('dbSchema')}"
    username = getInstanceConfig('dbUser')
    password = getInstanceConfig('dbPassword')
    // ... driver, pool settings, etc.
}
```

Instance configs can also be read from service or bootstrap code at any time:

```groovy
import static io.xh.hoist.util.InstanceConfigUtils.getInstanceConfig

String customEndpoint = getInstanceConfig('myExternalApiUrl')
```

All values are returned as `String` — callers are responsible for any type conversion.

---

## Soft Configuration

### Overview

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

### Source Files

| File | Location | Role |
|------|----------|------|
| `AppConfig` | `grails-app/domain/io/xh/hoist/config/` | GORM domain — database-backed config entries |
| `ConfigService` | `grails-app/services/io/xh/hoist/config/` | Primary service — typed getters, event publishing |
| `ConfigDiffService` | `grails-app/services/io/xh/hoist/config/` | Cross-environment config synchronization |
| `ConfigAdminController` | `grails-app/controllers/io/xh/hoist/admin/` | Admin console endpoint for config management |

### Key Classes

#### AppConfig

A GORM domain class representing a single configuration entry. Stored in the `xh_config` table.

| Property | Type | Description |
|----------|------|-------------|
| `name` | `String` | Unique config name (max 50 chars) |
| `value` | `String` | Stored value (TEXT column for large content, `blank: false` — cannot be empty string) |
| `valueType` | `String` | One of: `string`, `int`, `long`, `double`, `bool`, `json`, `pwd` |
| `clientVisible` | `boolean` | Include in client-side config payload |
| `groupName` | `String` | Logical grouping for Admin Console display (default: `'Default'`) |
| `note` | `String` | Optional description (max 1200 chars) |
| `lastUpdated` | `Date` | Timestamp of last change |
| `lastUpdatedBy` | `String` | Username of last modifier |

##### Value Types

| Type | Stored As | Parsed To | Notes |
|------|-----------|-----------|-------|
| `string` | Plain text | `String` | |
| `int` | Integer string | `Integer` | Validated on save |
| `long` | Long string | `Long` | Validated on save |
| `double` | Double string | `Double` | Validated on save |
| `bool` | `"true"` or `"false"` | `Boolean` | Case-sensitive string |
| `json` | JSON string | `Map` or `List` | Validated as parseable JSON |
| `pwd` | Encrypted string | `String` (decrypted) | Encrypted at rest via Jasypt |

##### Change Events

When an `AppConfig` value is updated, the domain's `beforeUpdate()` lifecycle hook fires a
cluster-wide `xhConfigChanged` event. The event only fires if the `value` field has actually changed
(checked via `hasChanged('value')`), and it is published asynchronously with a 500ms delay:

```groovy
// Event payload
[key: 'configName', value: parsedValue]
// Note: the value is produced by `obj.externalValue()` with no options. For `pwd` type configs,
// this returns the raw encrypted string — not the decrypted value.
```

This event is published to a Hazelcast topic, so all cluster instances receive it. Services can
react to config changes by subscribing to this topic (or using the `clearCachesConfigs` shortcut
on `BaseService`).

##### Instance Config Overrides

Any `AppConfig` value can be overridden via instance configuration (environment variables, YAML
files, or the instance config directory) without changing the database. The override takes
precedence when the config value is read:

```yaml
# In instance config
myApiEndpoint: https://staging-api.example.com
```

Override values are not encrypted, even for `pwd` type configs — they are treated as plaintext.
If an override value cannot be parsed to the declared type, it is silently ignored (logged at TRACE).

#### ConfigService

The primary service for reading and managing configuration values.

##### Typed Getters

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

##### `getStringList(configName)`

Parses a config value into a list of trimmed strings. Supports a special `[configName]` syntax for
referencing other configs:

```groovy
// Config 'allowedRoles' = "ADMIN, TRADER, [extraRoles]"
// Config 'extraRoles' = "VIEWER, AUDITOR"
List<String> roles = configService.getStringList('allowedRoles')
// → ['ADMIN', 'TRADER', 'VIEWER', 'AUDITOR']
```

##### `setValue(name, value, lastUpdatedBy?)`

Updates an existing config's value. Returns the updated `AppConfig` instance. Automatically
serializes objects to JSON for `json` type configs. Triggers the `xhConfigChanged` event.

The full signature is:
```groovy
AppConfig setValue(String name, Object value, String lastUpdatedBy = authUsername ?: 'hoist-config-service')
```

```groovy
configService.setValue('maxRetries', 5)
configService.setValue('dashboardSettings', [theme: 'dark', layout: 'grid'])
configService.setValue('apiEndpoint', 'https://new-api.example.com', 'batch-job')
```

##### `getClientConfig()`

Returns a map of all `clientVisible` configs for the hoist-react client. Passwords are automatically
obscured as `'*********'`, and JSON values are parsed to objects.

##### `ensureRequiredConfigsCreated(reqConfigs)`

Called during application bootstrap to declare configs the application depends on. Creates missing
configs with default values; logs errors for type mismatches or `clientVisible` flag mismatches on
existing configs (but does not auto-fix them).

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

##### `hasConfig(name)`

Checks whether a config with the given name exists. Useful for conditional logic around optional
configs.

#### ConfigDiffService

Handles cross-environment config synchronization. The `applyRemoteValues()` method accepts a list
of records from a remote environment and creates, updates, or deletes local configs to match:

- If a config doesn't exist locally, it is created with the remote values.
- If a config exists and remote values are provided, it is updated.
- If a config exists but the remote value is `null`, it is deleted.

This powers the Admin Console's "Config Differ" tool for comparing and syncing configs across
development, staging, and production environments.

### Naming Conventions

Hoist's own framework configs are prefixed with `xh`. Applications should choose a distinct prefix
(e.g., the app name) to avoid collisions.

| Config | Type | Description |
|--------|------|-------------|
| Various `xh*` configs | Mixed | Framework configs created by hoist-core services |
| App-specific configs | Mixed | Created via `ensureRequiredConfigsCreated()` |

### Common Patterns

#### Reactive Config Usage

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

#### Timer Intervals from Config

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

#### JSON Configs for Complex Settings

Use `json` type configs for structured settings that would be unwieldy as multiple scalar configs:

```groovy
Map trackingConfig = configService.getMap('xhActivityTrackingConfig')
int maxRows = trackingConfig.maxRows ?: 50000
boolean enabled = trackingConfig.enabled ?: true
```

### Client Integration

Configs with `clientVisible: true` are automatically included in the client config payload fetched
by hoist-react during `XH.initAsync()`. The client accesses them via `XH.getConf()`:

```javascript
// Client-side (hoist-react)
const endpoint = XH.getConf('apiEndpoint');
const settings = XH.getConf('dashboardSettings');  // parsed JSON object
```

Password configs are always obscured when sent to the client, even if marked `clientVisible`.

### Common Pitfalls

#### Accessing raw value without typed getter

Always use the typed getters on `ConfigService` rather than querying `AppConfig` domain objects
directly:

```groovy
// ✅ Do: Use typed getter
String region = configService.getString('myAppRegion')

// ❌ Don't: Query domain directly
def region = AppConfig.findByName('myAppRegion').value
```

The typed getters handle type coercion, password decryption, instance config overrides, and caching.

#### Not declaring required configs

If application code depends on a config that doesn't exist, the typed getter will throw a
`RuntimeException`. Always declare required configs in `ensureRequiredConfigsCreated()` during
bootstrap to ensure they exist with sensible defaults.

#### Modifying `pwd` configs via direct domain access

Password configs must go through `AppConfig`'s lifecycle hooks for encryption. Setting
`appConfig.value = 'newSecret'` and saving will encrypt the value. But bypassing the domain (e.g.,
raw SQL) will store plaintext, breaking decryption.

#### Ignoring instance config overrides

When troubleshooting config issues, remember to check for instance config overrides. A config may
have one value in the database but a different effective value from an environment variable or YAML
file. The Admin Console shows override values when present.
