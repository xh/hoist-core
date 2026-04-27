# Configuration

## Overview

The soft configuration system is one of the most widely used and important features in Hoist.
Nearly every application relies on it extensively to manage runtime-adjustable settings, feature
flags, API endpoints, thresholds, and more — all without requiring code changes or redeployments.

Hoist applications are configured through two complementary systems:

1. **Instance configuration** — Low-level, deployment-specific settings loaded at startup via
   environment variables. These provide the database connection, environment identity, and other
   values that must be in place before the application can fully initialize.
2. **Soft configuration** — Database-backed `AppConfig` entries that are runtime-adjustable via the
   Admin Console without code changes or redeployment.

Instance configs bootstrap the app; soft configs tune it once it's running.

### When to Use Soft Configs

Soft configs should be the default choice for application settings. Reach for them whenever a
value might need to change without a code deployment. Common cases include:

- **Avoiding magic numbers** — Thresholds, limits, and feature flags that are hard-coded today but
-
  may need adjustment. Extracting them into configs makes them adjustable at runtime via the Admin
  Console.
- **Per-environment tuning** — Each deployment environment (Development, Staging, Production) has
  its own config database, so values can be deliberately kept different across environments.
  Examples include pointing to a dev API host in Development, extending timeouts in a QA/UAT
  environment, or enabling verbose logging in Staging while keeping it off in Production.
- **Runtime experimentation** — Values that operators or developers may want to tweak without a
  release cycle, such as polling intervals, batch sizes, or display settings.

Because each environment maintains its own config values independently, the Admin Console's
"Config Differ" tool can be used to compare and selectively sync configs across environments —
keeping intentional differences in place while catching unintended drift.

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

Where `[APP_CODE]` and `[KEY]` are both in UPPER_SNAKE_CASE. The conversion uses Jackson's
`UpperSnakeCaseStrategy` and also replaces hyphens with underscores — so an app code like
`my-app` becomes `MY_APP`:

| Code Key | Environment Variable (for app `my-app`) |
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
| `otlpEnabledInLocalDev` | Set to `'true'` to allow OTLP export of metrics and traces while running in local development. Defaults to `'false'` and has no effect outside of local dev. |

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

##### Typed configs via `TypedConfigMap`

For structured JSON configs with a stable, known key set, declare a `TypedConfigMap` subclass
and wire it in via the `typedClass:` key on the config's `ensureRequiredConfigsCreated`
entry. This gives you:

1. **One source of truth for shape + defaults.** Property initializers on the class are the
   fallback values; when the stored map is missing a key, the declared default applies.
2. **Typed server-side reads** via `configService.getTypedConfig(Class)`.
3. **Populated client payloads** — for `clientVisible: true` configs, the map sent to the
   client via `getClientConfig()` is filled in through the typed class, so the same defaults
   reach browser code without duplicating them in TypeScript.
4. **Startup drift detection** — a `WARN` is logged when a typed-class property default
   disagrees with the BootStrap `defaultValue` for the same key, flagging stale declarations
   on either side.
5. **Unknown keys** are logged at WARN and ignored — stale or mistyped entries in soft config
   surface without breaking startup.

Example:

```groovy
// 1. Declare the typed class. Property initializers are the defaults.
class PricingConfig extends TypedConfigMap {
    String getConfigName() { 'pricingSourceConfig' }

    /** Upstream HTTP endpoint. */
    String endpoint = 'https://prices.example.com'
    /** Timeout (ms) for a single price fetch. */
    Integer timeoutMs = 5000
    /** Whether to fall back to last-known prices when the upstream is unavailable. */
    boolean fallbackEnabled = true

    PricingConfig(Map args) { init(args) }
}

// 2. Register it in BootStrap alongside the other config metadata.
configService.ensureRequiredConfigsCreated([
    pricingSourceConfig: [
        valueType: 'json',
        defaultValue: [endpoint: 'https://prices.example.com', timeoutMs: 5000, fallbackEnabled: true],
        typedClass: PricingConfig,
        groupName: 'Pricing',
        note: '...'
    ]
])

// 3. Read it, anywhere.
PricingConfig config = configService.getTypedConfig(PricingConfig)
```

`typedClass:` is **fully optional** — entries without it retain the prior behavior (raw map
served to clients via `getClientConfig()`, inline fallbacks at call sites). Recommended when
a config has a stable, known set of keys you want typed and documented. Not recommended for
free-form key/value maps (e.g. feature-flag bags keyed by arbitrary strings) or list-valued
configs — continue using `getMap` / `getList` for those.

**Nested shapes.** A property whose declared type is itself a `TypedConfigMap` subclass is
populated recursively — existing defaults on the nested instance are preserved for any keys
the stored value doesn't supply. Likewise, a `List<Foo>` property where `Foo extends
TypedConfigMap` converts each supplied map to a typed `Foo` instance. See
`ActivityTrackingConfig` (nested `ClientHealthReport` and `MaxRows`) and `LdapConfig`
(nested `List<LdapServerOptions>`) in hoist-core for in-framework examples.

**Construction — call `init(args)` in the constructor body, not `super(args)`.** Each
subclass declares a single `MyConfig(Map args) { init(args) }` constructor. Do NOT write a
subclass constructor that calls `super(args)`: Java/Groovy run subclass field initializers
*after* the super constructor returns, so any values `init(args)` set via super would be
silently overwritten by declared defaults. Calling `init(args)` in the subclass body runs
after the initializers, which is the order we need. `getTypedConfig` and the nested-shape
machinery both invoke the Map constructor for you — you should rarely call it directly.

In-framework examples: `MonitorConfig`, `TraceConfig`, `MetricsConfig`, `ActivityTrackingConfig`,
`AlertBannerConfig`, `ChangelogConfig`, `ClientErrorConfig`, `ConnPoolMonitoringConfig`,
`EnvPollConfig`, `ExportConfig`, `IdleConfig`, `LdapConfig`, `LogArchiveConfig`,
`MemoryMonitoringConfig`, `WebSocketConfig`.

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

Applications should declare all long-lived, expected configs here — not just those that are
strictly required at startup. This serves as an effective inventory of the application's soft
configs, ensures that an app starting against a fresh database has a complete set of entries
visible and adjustable in the Admin Console, and guarantees consistency across environments.

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

An internal implementation service that powers the Admin Console's "Config Differ" tool, enabling
comparison and synchronization of configs across environments.

### Naming Conventions

Hoist's own framework configs are prefixed with `xh` (e.g., `xhActivityTrackingConfig`,
`xhEmailFilter`). Applications should avoid the `xh` prefix to prevent collisions with framework
configs, but do not need to namespace their own configs with an app-specific prefix — each app is
the sole consumer of its own config entries.

Choose clear, legible, specific names that are easy to understand at a glance. The recommended
convention is `camelCase` (e.g., `pricingApiHost`, `maxRetries`, `dashboardSettings`). Include
units in the name where relevant to understanding the value (e.g., `pricingApiTimeoutSecs`).

### Common Patterns

#### Externalizing Magic Numbers and Thresholds

A common use of soft configs is to extract values that might need to change — thresholds, limits,
feature flags — out of the source code and into the Admin Console:

```groovy
int maxResults = configService.getInt('tradeBlotterMaxRows')
boolean auditsEnabled = configService.getBool('auditLoggingEnabled')
```

This avoids hard-coding values that may need to vary across environments or be adjusted without a
redeployment.

#### Timer Intervals from Config

`BaseService.createTimer()` accepts a config name (string) as its `interval` parameter. The config
must be of type `int`, and its value is looked up via `configService.getInt()` on each evaluation.
The timer automatically adjusts when the config value changes:

```groovy
createTimer(
    name: 'refresh',
    runFn: this.&refresh,
    interval: 'xhRefreshIntervalSecs',   // reads interval from this int config
    intervalUnits: SECONDS,
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

#### Reactive Config Usage and clearCachesConfigs

Whenever a service caches a resource that was created using a config value, developers should
consider whether that resource needs to be recreated if the config changes. For example, a service
that caches a reference to an HTTP client configured with a host from config should
invalidate that cached client if the host config is updated — otherwise the service will continue
using the stale value.

The easiest way to handle this is via the `clearCachesConfigs` static property on `BaseService`.
List the config names that should trigger a `clearCaches()` call — the framework subscribes to the
`xhConfigChanged` topic and handles the rest:

```groovy
class PricingService extends BaseService {

    static clearCachesConfigs = ['pricingSourceConfig']

    def configService

    // This cache holds data fetched based on the 'pricingSourceConfig' config.
    // If that config changes, the cached data is stale and must be refetched.
    CachedValue<Map> prices = createCachedValue(name: 'prices', replicate: true)

    Map getPrices() {
        prices.getOrCreate { fetchPrices() }
    }

    private Map fetchPrices() {
        Map sourceConfig = configService.getMap('pricingSourceConfig')
        // ... fetch from source using config-driven host, credentials, etc.
    }

    void clearCaches() {
        prices.clear()
        super.clearCaches()
    }
}
```

For cases where `clearCaches()` is not the right response — e.g., you need to call a specific
method or take a more targeted action — subscribe to the `xhConfigChanged` topic directly:

```groovy
class PricingService extends BaseService {

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

### Common Pitfalls

#### Avoid ambiguous or unclear config key names

Config names are the primary interface for developers and admins working with soft configuration.
Vague or overly abbreviated names make it harder to understand what a config controls, especially
when browsing the Admin Console. Avoid generic names like `timeout`, `enabled`, or `maxCount` that
don't convey what they apply to:

```groovy
// ✅ Do: Specific, self-documenting names
'pricingApiTimeoutSecs'
'feedbackEmailEnabled'
'tradeBlotterMaxRows'

// ❌ Don't: Ambiguous names that require hunting for context
'timeout'
'enabled'
'maxRows'
```

#### Don't access raw values without typed getters

Always use the typed getters on `ConfigService` rather than querying `AppConfig` domain objects
directly:

```groovy
// ✅ Do: Use typed getter
String region = configService.getString('myAppRegion')

// ❌ Don't: Query domain directly
def region = AppConfig.findByName('myAppRegion').value
```

The typed getters handle type coercion, password decryption, instance config overrides, and caching.

#### Don't skip declaring configs in `ensureRequiredConfigsCreated`

If application code depends on a config that doesn't exist, the typed getter will throw a
`RuntimeException`. More broadly, all long-lived app configs should be declared in
`ensureRequiredConfigsCreated()` during bootstrap — this creates a complete inventory, ensures
fresh databases start with viewable and adjustable entries in the Admin Console, and prevents
missing-config surprises across environments.

#### Don't use instance configs when soft configs would suffice

Favor `AppConfig` soft configs wherever possible — they can be adjusted at runtime via the Admin
Console without restarting the application. Instance configs require an environment variable change
and a restart to take effect. Reserve instance configs for values that are needed very early in the
startup process (e.g., database credentials, `serverURL`) or that are too sensitive to store in
the database, even encrypted.

#### Don't use instance configs in `application.groovy`

`InstanceConfigUtils` is not available in `application.groovy` — that file is processed before
compilation. Use `runtime.groovy` instead for any configuration that depends on instance config
values.

#### Don't forget to check for instance config overrides

When troubleshooting config issues, remember to check for instance config overrides. A config may
have one value in the database but a different effective value from an environment variable or YAML
file. The Admin Console shows override values when present.
