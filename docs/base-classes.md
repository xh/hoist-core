# Base Classes

## Overview

Hoist-core provides three abstract base classes that form the foundation for all server-side code:

- **`BaseService`** — The abstract superclass for all Grails services. Provides a managed lifecycle,
  distributed resource factories (caches, timers, Hazelcast maps and sets), event subscriptions,
  identity access, logging, and admin console integration.
- **`BaseController`** — The abstract superclass for all controllers. Provides JSON
  serialization/parsing, async support, OWASP encoding, identity access, and logging.
- **`RestController`** — Extends `BaseController` with a template-method CRUD pattern for managing
  GORM domain objects via REST endpoints.

Together, these classes establish the conventions and capabilities that every Hoist application
service and endpoint inherits.

## Source Files

| File | Location | Role |
|------|----------|------|
| `BaseService` | `src/main/groovy/io/xh/hoist/` | Abstract superclass for all services |
| `BaseController` | `grails-app/controllers/io/xh/hoist/` | Abstract superclass for all controllers |
| `RestController` | `grails-app/controllers/io/xh/hoist/` | CRUD controller for GORM domain classes |
| `Timer` | `src/main/groovy/io/xh/hoist/util/` | Managed polling timer with `primaryOnly` support |

The `Cache` and `CachedValue` classes created by the factory methods below are documented in
[`caching.md`](./caching.md).

## BaseService

### Lifecycle

Every Hoist service extends `BaseService` and follows a well-defined lifecycle:

1. **Spring construction** — Grails creates the service as a Spring-managed singleton.
2. **`init()`** — Called during application bootstrap via `initialize()` or `parallelInit()`.
   Override to set up starting state, create resources, and subscribe to events.
3. **Runtime** — The service handles requests and runs timers.
4. **`clearCaches()`** — Can be called at any time (including from the Admin Console) to reset
   service state. The base implementation only updates a `lastCachesCleared` timestamp — it does
   **not** automatically clear caches created via `createCache()` or `createCachedValue()`. Override
   to explicitly clear each cache and reset any other custom state. See
   [`caching.md`](./caching.md) on clearing and invalidating caches.
5. **`destroy()`** — Called by Spring on clean shutdown. Cancels all managed timers.

The status methods `isInitialized()` and `isDestroyed()` can be used for defensive checks during
startup and shutdown.

```groovy
class PortfolioService extends BaseService {

    def configService

    private Cache<String, Map> portfolioCache

    void init() {
        portfolioCache = createCache(name: 'portfolios', expireTime: 10 * MINUTES)
        subscribe('xhConfigChanged', {clearCaches()})
    }

    void clearCaches() {
        super.clearCaches()
        portfolioCache.clear()
    }
}
```

#### Parallel Initialization

`BaseService` provides a static method
`parallelInit(Collection<BaseService> services, Long timeout = 30 * SECONDS)` that initializes all
provided services in parallel (blocking until all complete). The application's `BootStrap` calls
this method multiple times with different groups of services to control startup ordering:

```groovy
// In BootStrap.groovy — the caller decides the groups, not the services themselves.
BaseService.parallelInit([configService, prefService])       // group 1
BaseService.parallelInit([portfolioService, marketService])  // group 2
```

### Resource Factories

`BaseService` provides factory methods that create *managed* resources — resources that are named,
tracked by the framework, and reported in the Admin Console.

**All resource names must be unique within a service** across caches, timers, and distributed
Hazelcast objects.

#### `createCache()`

Creates a `Cache<K, V>` — a key-value store with optional entry expiry, backed by a Hazelcast
`ReplicatedMap` when clustered or a local `ConcurrentHashMap` otherwise.

```groovy
private Cache<String, List<Position>> positionCache

void init() {
    positionCache = createCache(name: 'positions', expireTime: 5 * MINUTES, replicate: true)
}
```

Accepts `name`, `expireTime`, `expireFn`, `timestampFn`, `replicate`, `serializeOldValue`, and
`onChange`. See [`caching.md`](./caching.md) for the full parameter and API reference, expiry
semantics, change-handler threading, and pitfalls.

#### `createCachedValue()`

Creates a `CachedValue<V>` — a single-value cache with a closely parallel set of expiry and
replication options. Ideal for an expensive computation that should be shared across a cluster.

```groovy
private CachedValue<Map> summary

void init() {
    summary = createCachedValue(name: 'summary', replicate: true, expireTime: 30 * MINUTES)
}
```

Accepts `name`, `expireTime`, `expireFn`, `timestampFn`, `replicate`, and `onChange`. These mirror
their `createCache()` counterparts but do not behave identically. See
[`caching.md`](./caching.md#differences-from-cache) for how the two diverge.

#### `createTimer()`

Creates a managed `Timer` that runs a closure on a configurable interval. The Timer ensures that
only one execution runs at a time. Supports `primaryOnly` mode so that the task runs on only one
cluster instance.

```groovy
void init() {
    createTimer(
        name: 'refreshData',
        runFn: this.&refreshData,
        interval: 5 * MINUTES,
        primaryOnly: true,            // only run on the primary instance
        delay: true,                  // wait one interval before first run
        timeout: 3 * MINUTES          // max execution time (cancels task and logs error on timeout)
    )
}

private void refreshData() {
    // Called periodically by the timer
}
```

Constructor parameters:

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `name` | `String` | required | Unique name within the service |
| `runFn` | `Closure` | `null` | Closure to execute. If omitted, the timer calls `onTimer()` on the owning service |
| `interval` | `Number`, `Closure`, or `String` | `null` | Interval between runs. A string is interpreted as an `AppConfig` name. Non-positive values disable the timer |
| `intervalUnits` | `Long` | `1` | Multiplier applied to the `interval` value (e.g. `SECONDS`) |
| `timeout` | `Number`, `Closure`, or `String` | `3 * MINUTES` | Max execution time. On timeout, the running task is **cancelled** (interrupted) and the timeout is logged as an error |
| `timeoutUnits` | `Long` | `1` | Multiplier applied to the `timeout` value (e.g. `SECONDS`) |
| `primaryOnly` | `Boolean` | `false` | Only run on the primary cluster instance |
| `delay` | `Boolean` or `Long` | `false` | Delay before first run. `true` = one interval; a number = delay in ms |
| `runImmediatelyAndBlock` | `Boolean` | `false` | Run the function synchronously during construction and block until complete |

When `interval` is specified as a string, it is treated as an `AppConfig` key — the timer will
automatically adjust when the config value changes. Use `intervalUnits` to ensure proper unit
interpretation (e.g. when a config name like `xhMarketDataRefreshSecs` stores seconds, set
`intervalUnits: SECONDS`). Intervals below 500ms are clamped to 500ms.

Key `Timer` API methods:

| Method | Description |
|--------|-------------|
| `forceRun()` | Request an immediate execution on the next heartbeat, or as soon as any in-progress run completes |
| `cancel()` | Permanently cancel the timer. In-progress executions are unaffected |

#### `createIMap()`

Creates a Hazelcast `IMap<K, V>` — a distributed, partitioned map. Unlike `Cache` (which uses
`ReplicatedMap` and is fully replicated), `IMap` partitions data across cluster members and is
better suited for large datasets.

```groovy
private IMap<String, byte[]> fileStore

void init() {
    fileStore = createIMap('fileStore')
}
```

#### `createISet()`

Creates a Hazelcast `ISet<V>` — a distributed set. Useful for tracking unique items across the
cluster (e.g. a set of active session IDs).

#### `createReplicatedMap()`

Creates a Hazelcast `ReplicatedMap<K, V>` — a fully replicated map where every instance holds a
complete copy. This is what `Cache` uses internally.

#### `getTopic()`

Returns a Hazelcast `ITopic<M>` for cluster-wide pub/sub messaging. Unlike the factory methods
above, `getTopic()` does not register the topic as a managed resource. Use `subscribeToTopic()` to
receive messages; use `getTopic()` directly when you need to **publish** messages:

```groovy
getTopic('myCustomEvent').publish([action: 'refresh', source: username])
```

### Event Subscriptions

#### `subscribe()` — Local Grails Events

Subscribe to events on the local Grails event bus (single instance only):

```groovy
void init() {
    subscribe('xhPortfolioLoaded') { Map msg ->
        logInfo("Portfolio loaded", msg.portfolioId)
        refreshSummary()
    }
}
```

The subscription automatically catches and logs exceptions (unlike raw Grails `EventBus.subscribe`,
which silently swallows them) and skips events on destroyed services.

> **Note:** `subscribe()` receives events on the local instance only. For cluster-wide events
> (including config changes), use `subscribeToTopic()` or `clearCachesConfigs` instead.

#### `subscribeToTopic()` — Cluster-wide Pub/Sub

Subscribe to a Hazelcast topic for cluster-wide messaging:

```groovy
void init() {
    subscribeToTopic(
        topic: 'xhConfigChanged',
        onMessage: { Map msg -> handleConfigChange(msg) },
        primaryOnly: false     // receive on all instances (default)
    )
}
```

This is the preferred method for most pub/sub use cases since it works across multiple instances.

#### `clearCachesConfigs` — Auto-clear on Config Change

Declare a static list of config names that should trigger `clearCaches()` when changed:

```groovy
class MyService extends BaseService {
    static clearCachesConfigs = ['myServiceConfig', 'myOtherConfig']
}
```

This wires up a `subscribeToTopic` listener automatically — no manual subscription needed.

### Logging

`BaseService` implements the `LogSupport` trait, providing structured logging methods —
`logTrace()`, `logDebug()`, `logInfo()`, `logWarn()`, `logError()` — and timed execution blocks via
`withTrace()`, `withDebug()`, and `withInfo()`. See [`logging.md`](./logging.md) for full details.

### Identity Access

`BaseService` implements `IdentitySupport`, providing access to the current user:

| Method | Returns |
|--------|---------|
| `getUser()` | Current active `HoistUser` (accounts for impersonation) |
| `getUsername()` | Current active username |
| `getAuthUser()` | Authenticated `HoistUser` (ignores impersonation) |
| `getAuthUsername()` | Authenticated username |

These delegate to `IdentityService` and return `null` outside a request context.

### Admin Console Integration

Services can expose monitoring data to the Admin Console:

```groovy
Map getAdminStats() {
    [
        activeConnections: connections.size(),
        lastRefresh: lastRefreshDate,
        *: configForAdminStats('myServiceConfig')  // include relevant config values
    ]
}
```

Timer, Cache, and CachedValue resources are **automatically** reported — no need to include them
in `getAdminStats()`.

Override `getComparableAdminStats()` to declare which stats keys should be compared across cluster
instances:

```groovy
List<String> getComparableAdminStats() { ['activeConnections'] }
```

The Hoist Admin Console exposes all of this data in the **Servers > Objects** tab via
`ClusterObjectsReport`. That tab lists every managed resource (caches, timers, cached values) and
service stat across all cluster instances. For keys declared via `getComparableAdminStats()`, the
report runs a cross-instance comparison and flags any "breaks" — values that differ between
instances. These breaks also feed into the built-in `objectBreaks` status monitor provided by
`DefaultMonitorDefinitionService`, which can alert operators when cluster state has diverged.

### Cluster Awareness

`BaseService` provides `getIsPrimary()` to check whether the current instance is the primary
(oldest) member of the cluster. This is used internally by `primaryOnly` timers but can also be
checked directly in service code.

## BaseController

`BaseController` is the abstract superclass for all Hoist controllers. It provides JSON rendering,
request parsing, async support, identity access, and logging (via `LogSupport` — see
[`logging.md`](./logging.md)).

### Key Methods

| Method | Description |
|--------|-------------|
| `renderJSON(Object o)` | Serialize an object to JSON via Hoist's `JSONSerializer` and write to response |
| `parseRequestJSON(Map opts)` | Parse request body as a JSON object (Map). Pass `safeEncode: true` to OWASP-encode input |
| `parseRequestJSONArray(Map opts)` | Parse request body as a JSON array (List) |
| `renderSuccess()` | Render an empty 204 No Content response |
| `renderClusterJSON(ClusterResult)` | Render a result from a cluster-delegated operation |
| `runAsync(Closure)` | Execute a closure asynchronously as a Grails `Promise` |
| `safeEncode(String)` | Run input through OWASP HTML content encoder |

**Avoid** using Grails' built-in `render` or `request.getJSON()` — always use `renderJSON()` and
`parseRequestJSON()` to ensure consistent Jackson-based serialization.

### Identity Access

`BaseController` implements `IdentitySupport` with the same methods as `BaseService` — `getUser()`,
`getUsername()`, `getAuthUser()`, `getAuthUsername()`.

### Exception Handling

Uncaught exceptions are handled by `handleException()`, which delegates to the framework's
`ExceptionHandler`. This renders the exception as a JSON error response with an appropriate HTTP
status code.

## RestController

`RestController` extends `BaseController` with a template-method pattern for CRUD operations on
GORM domain classes. It maps standard REST HTTP methods to controller actions and provides hooks
for customization.

### Setup

Point the controller at a GORM domain class and optionally enable audit tracking:

```groovy
class PositionController extends RestController {
    static restTarget = Position       // GORM domain class to manage
    static trackChanges = true         // log CRUD operations via TrackService
}
```

### URL Mapping

`UrlMappings` routes REST requests to `RestController` actions:

```
/rest/$controller/$id?       →  POST=create, GET=read, PUT=update, DELETE=delete
/rest/$controller/bulkUpdate →  Apply updates to multiple records
/rest/$controller/bulkDelete →  Delete multiple records
/rest/$controller/lookupData →  Custom lookup data (controller must implement)
```

The `bulkUpdate` action parses a JSON request body `{ids: [...], newParams: {...}}` and applies
`doUpdate()` to each record, returning `{success: N, fail: N}`. The `bulkDelete` action reads `ids`
from query/form parameters (via `params.list('ids')`) and applies `doDelete()` to each, also
returning success/fail counts. Both continue processing on individual record failures.

### Template Methods

Override these methods to customize CRUD behavior:

| Method | Default Behavior | Override When |
|--------|-----------------|---------------|
| `doCreate(obj, data)` | `obj.save(flush: true)` | You need custom creation logic |
| `doList(query)` | Throws `RuntimeException` if `query` is non-empty; otherwise `restTargetVal.list()` | You need filtering, sorting, or query support |
| `doUpdate(obj, data)` | `bindData(obj, data); obj.save(flush: true)` | You need custom update logic |
| `doDelete(obj)` | `obj.delete(flush: true)` | You need soft-delete or cascading logic |
| `preprocessSubmit(Map submit)` | No-op | You need to transform data before create/update |

> **Note:** The default `doList(Map query)` throws a `RuntimeException` when `query` is non-empty.
> It only falls through to `restTargetVal.list()` when query is empty/falsy. Any controller that
> needs to support query parameters **must** override `doList`.

```groovy
class PositionController extends RestController {
    static restTarget = Position

    @Override
    protected List doList(Map query) {
        def fund = query.fund
        fund ? Position.findAllByFund(fund) : Position.list()
    }

    @Override
    protected void doCreate(Object obj, Object data) {
        obj.createdBy = username
        obj.save(flush: true)
    }
}
```

### Audit Tracking

When `trackChanges = true`, every create, update, and delete is logged via `TrackService` with the
`Audit` category. Override `trackChange()` to customize the tracking behavior.

## Common Patterns

### Service-to-Service Communication

Services are Spring-managed singletons. **Within services and controllers**, access other services
via Grails dependency injection:

```groovy
class PortfolioService extends BaseService {
    def configService      // injected by Grails
    def marketDataService
}
```

**Outside of Spring-managed classes** (POGOs, domain objects, utility classes), services are not
injected. Instead, use the static typed accessors on `io.xh.hoist.util.Utils`:

```groovy
import static io.xh.hoist.util.Utils.getConfigService

class PortfolioSummary {
    Map compute() {
        def threshold = configService.getDouble('portfolioThreshold')
        // ...
    }
}
```

Hoist's `Utils` provides typed accessors for core framework services (`configService`,
`prefService`, `identityService`, etc.). Applications can create their own `Utils` class following
the same pattern to expose app-level services:

```groovy
class Utils {
    static PortfolioService getPortfolioService() {
        return (PortfolioService) io.xh.hoist.util.Utils.appContext.portfolioService
    }
}
```

### Cache-centric Patterns

The `getOrCreate` lazy-computation pattern and the timer-driven refresh pattern (a `primaryOnly`
timer populating a replicated cache) are documented in [`caching.md`](./caching.md), along with the
use of `Timer.forceRun()` to repopulate a cache from within `clearCaches()`.

## Client Integration

The `BaseController.renderJSON()` and `parseRequestJSON()` methods form the bridge between server
and client. Client-side hoist-react code communicates with controllers via `FetchService`, which
expects JSON request and response bodies following Hoist conventions.

`RestController` endpoints (`/rest/$controller`) are consumed by hoist-react's `RestGridModel` and
`RestStore`, which map grid CRUD operations to the standard REST actions.

See [`json-handling.md`](./json-handling.md) for details on JSON serialization conventions.

## Common Pitfalls

### Forgetting to call `super.clearCaches()`

When overriding `clearCaches()`, always call `super.clearCaches()` to ensure the framework updates
its internal tracking (e.g. `lastCachesCleared` timestamp):

```groovy
// ✅ Do: Call super
void clearCaches() {
    super.clearCaches()
    myCustomState = null
}

// ❌ Don't: Skip super
void clearCaches() {
    myCustomState = null
}
```

### Non-unique resource names

All resources created via factory methods (`createCache`, `createCachedValue`, `createTimer`,
`createIMap`, `createISet`) share a single namespace within each service. Using the same name for a
cache and a timer will throw a `RuntimeException` at startup.

### Cache-related pitfalls

Pitfalls specific to `Cache` and `CachedValue` — synchronous vs. asynchronous `onChange` delivery,
`replicate` silently changing handler timing, caching null, and inexact `size()` — are covered in
[`caching.md`](./caching.md).

### Using Grails `render` instead of `renderJSON`

Grails' built-in `render` method uses a different JSON converter. Always use `renderJSON()` for JSON
responses to ensure consistent Jackson-based serialization with support for `JSONFormat` and custom
serializers.

The exception is **file and binary responses** — use Grails `render` directly when returning
non-JSON content such as Excel exports, PDFs, or log file downloads:

```groovy
render(file: fileBytes, fileName: 'export.xlsx', contentType: 'application/octet-stream')
```

### Long-running `init()` methods

Service `init()` runs during application startup with a default timeout of 30 seconds. If a service
needs to load data that takes time, consider whether it truly needs to be ready before the app
starts serving requests:

- **If it does** — that's fine, leave the work in `init()`. If it regularly exceeds the default
  timeout, increase it via the `timeout` parameter to `parallelInit()` or `initialize()`.
- **If it doesn't** — create a timer without `runImmediatelyAndBlock`. The timer will kick off its
  first run right away but asynchronously, allowing `init()` to return and app startup to continue.
  Use `delay: true` if you want to wait one full interval before the first run.
