> **Status: DRAFT** — This document is awaiting review. Content may be incomplete or subject to
> change. Do not remove this banner until the document has been interactively reviewed and approved.

# Base Classes

## Overview

Hoist-core provides three abstract base classes that form the foundation for all server-side code:

- **`BaseService`** — The abstract superclass for all Grails services. Provides a managed lifecycle,
  distributed resource factories (caches, timers, Hazelcast maps), event subscriptions, identity
  access, and admin console integration.
- **`BaseController`** — The abstract superclass for all controllers. Provides JSON
  serialization/parsing, async support, OWASP encoding, and identity access.
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
| `Cache` | `src/main/groovy/io/xh/hoist/cache/` | Distributed key-value cache (Hazelcast-backed) |
| `CachedValue` | `src/main/groovy/io/xh/hoist/cachedvalue/` | Distributed single-value cache |
| `Timer` | `src/main/groovy/io/xh/hoist/util/` | Managed polling timer with `primaryOnly` support |

## BaseService

### Lifecycle

Every Hoist service extends `BaseService` and follows a well-defined lifecycle:

1. **Spring construction** — Grails creates the service as a Spring-managed singleton.
2. **`init()`** — Called after all services are constructed. Override to set up starting state,
   create resources, and subscribe to events. Services can declare `parallelInit` groups for
   concurrent startup (see below).
3. **Runtime** — The service handles requests and runs timers.
4. **`clearCaches()`** — Can be called at any time (including from the Admin Console) to reset
   service state. Override to clear custom state beyond managed caches.
5. **`destroy()`** — Called by Spring on clean shutdown. Cancels all managed timers.

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

`BaseService` provides a static method `parallelInit(Collection<BaseService> svcs)` that initializes
all provided services in parallel (blocking until all complete). The application's `BootStrap` calls
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

Creates a `Cache<K, V>` — a key-value store backed by a Hazelcast `ReplicatedMap` (when clustered)
or a local `ConcurrentHashMap`.

```groovy
private Cache<String, List<Position>> positionCache

void init() {
    positionCache = createCache(
        name: 'positions',
        expireTime: 5 * MINUTES,     // entries expire after this duration
        replicate: true,              // share across cluster (default: false)
        serializeOldValue: false      // performance optimization for large values
    )
}

// Usage — get-or-create pattern
List<Position> getPositions(String fundId) {
    positionCache.getOrCreate(fundId) {
        loadPositionsFromDb(fundId)
    }
}
```

Key `Cache` API methods:

| Method | Description |
|--------|-------------|
| `get(key)` | Get entry, or null |
| `getOrCreate(key, Closure)` | Get entry, creating it via the closure if absent |
| `put(key, value)` | Set entry |
| `remove(key)` | Remove entry |
| `clear()` | Clear all entries |
| `size()` | Number of entries |

#### `createCachedValue()`

Creates a `CachedValue<T>` — a single-value cache replicated across the cluster. Ideal for
expensive computations that should be shared.

```groovy
private CachedValue<Map> summary

void init() {
    summary = createCachedValue(
        name: 'summary',
        replicate: true,              // share across cluster (default: false)
        expireTime: 30 * MINUTES
    )
}

Map getSummary() {
    summary.getOrCreate {
        computeExpensiveSummary()
    }
}
```

Key `CachedValue` API methods:

| Method | Description |
|--------|-------------|
| `get()` | Get the value, or null |
| `getOrCreate(Closure)` | Get, computing via closure if absent or expired |
| `set(value)` | Set the value (replicates to cluster) |
| `clear()` | Clear the value |
| `isExpired()` | Check if the value has expired |

#### `createTimer()`

Creates a managed `Timer` that runs a closure on a configurable interval. Supports `primaryOnly`
mode so that the task runs on only one cluster instance.

```groovy
void init() {
    createTimer(
        name: 'refreshData',
        runFn: this.&refreshData,
        interval: 5 * MINUTES,
        primaryOnly: true,            // only run on the primary instance
        delay: true,                  // wait one interval before first run
        timeout: 3 * MINUTES          // max execution time before logging a warning
    )
}

private void refreshData() {
    // Called periodically by the timer
}
```

If no `runFn` is specified, the timer looks for an `onTimer()` method on the service.

The `interval` parameter can be sourced from an `AppConfig` by specifying its name — the timer
will automatically adjust when the config changes.

The `intervalUnits` parameter (default `1`, i.e. milliseconds) is a multiplier applied to the raw
`interval` value. When a config-driven interval name suggests seconds (e.g.
`xhMarketDataRefreshSecs`), verify that `intervalUnits` is set appropriately (e.g.
`intervalUnits: SECONDS`) so the timer does not misinterpret seconds as milliseconds.

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

#### `createReplicatedMap()`

Creates a Hazelcast `ReplicatedMap<K, V>` — a fully replicated map where every instance holds a
complete copy. This is what `Cache` uses internally.

### Event Subscriptions

#### `subscribe()` — Local Grails Events

Subscribe to events on the local Grails event bus (single instance only):

```groovy
void init() {
    subscribe('xhConfigChanged') { Map msg ->
        if (msg.key == 'myConfig') clearCaches()
    }
}
```

The subscription automatically catches and logs exceptions (unlike raw Grails `EventBus.subscribe`,
which silently swallows them) and skips events on destroyed services.

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

### Cluster Awareness

`BaseService` provides `getIsPrimary()` to check whether the current instance is the primary
(oldest) member of the cluster. This is used internally by `primaryOnly` timers but can also be
checked directly in service code.

## BaseController

`BaseController` is the abstract superclass for all Hoist controllers. It provides JSON rendering,
request parsing, async support, and identity access.

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
/rest/$controller/$id?  →  POST=create, GET=read, PUT=update, DELETE=delete
/rest/$controller/bulkUpdate
/rest/$controller/bulkDelete
```

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

Services are Spring-managed singletons. Access them via dependency injection or static `Utils`
accessors:

```groovy
class MyService extends BaseService {
    // Grails DI — preferred within services
    def configService
    def portfolioService

    // Static accessor — useful in non-service code
    static getPortfolioService() { Utils.appContext.portfolioService }
}
```

### Timer-driven Cache Refresh

A common pattern combines a timer with a cache for periodically refreshed data:

```groovy
class MarketDataService extends BaseService {

    private CachedValue<Map> marketData

    void init() {
        marketData = createCachedValue(name: 'marketData', replicate: true)
        createTimer(
            name: 'refreshMarketData',
            runFn: this.&refreshMarketData,
            interval: 'xhMarketDataRefreshSecs',  // interval from AppConfig
            primaryOnly: true
        )
    }

    Map getMarketData() {
        marketData.get()
    }

    private void refreshMarketData() {
        marketData.set(fetchFromExternalApi())
    }
}
```

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

All resources created via factory methods (`createCache`, `createTimer`, `createCachedValue`,
`createIMap`) share a single namespace within each service. Using the same name for a cache and a
timer will throw a `RuntimeException` at startup.

### Using Grails `render` instead of `renderJSON`

Grails' built-in `render` method uses a different JSON converter. Always use `renderJSON()` to
ensure consistent Jackson-based serialization with support for `JSONFormat` and custom serializers.

### Blocking in `init()`

Service `init()` runs during application startup. Long-running operations should be deferred to a
timer with `runImmediatelyAndBlock: true` (if the result is needed before the first request) or
`delay: true` (if it can load lazily).
