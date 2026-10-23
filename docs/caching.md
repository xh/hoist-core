# Caching

## Overview

Hoist provides two managed caching structures for holding computed or fetched data in memory, both
created through `BaseService` factory methods and both able to replicate across a cluster:

- **`Cache<K, V>`** — a key-value store with optional per-entry expiry. Backed by a
  `ConcurrentHashMap` locally, or a Hazelcast `ReplicatedMap` when clustered.
- **`CachedValue<V>`** — a single value, offering a closely parallel set of expiry and replication
  options. Backed by a Hazelcast `ReliableTopic` when clustered. The options are similar rather than
  identical: see [Differences from `Cache`](#differences-from-cache).

Both are *managed resources*: they are named, tracked by the framework, reported in the Admin
Console, and given their own logger. Neither should be constructed directly — always use
`createCache()` / `createCachedValue()` from `BaseService` so the framework can register them.

These structures are the right choice for small-to-medium datasets that are read frequently and are
cheap to rebuild. They are deliberately simple: there is no eviction policy, no size bound, and no
persistence. For large datasets that should be partitioned rather than copied to every instance, use
an `IMap` instead — see [Choosing a Structure](#choosing-a-structure).

## Source Files

| File | Location | Role |
|------|----------|------|
| `Cache` | `src/main/groovy/io/xh/hoist/cache/` | Key-value cache with optional expiry and replication |
| `CacheEntry` | `src/main/groovy/io/xh/hoist/cache/` | Wrapper holding a cached value plus key, timestamp, and serialization flag |
| `CacheEntryChanged` | `src/main/groovy/io/xh/hoist/cache/` | Event object passed to `Cache` change handlers |
| `CacheEntryListener` | `src/main/groovy/io/xh/hoist/cache/` | Internal Hazelcast `EntryListener` that drives handlers for a replicated `Cache` |
| `CachedValue` | `src/main/groovy/io/xh/hoist/cachedvalue/` | Single-value cache with optional expiry and replication |
| `CachedValueEntry` | `src/main/groovy/io/xh/hoist/cachedvalue/` | Wrapper holding the current value plus timestamp and identity |
| `CachedValueChanged` | `src/main/groovy/io/xh/hoist/cachedvalue/` | Event object passed to `CachedValue` change handlers |

## Choosing a Structure

| Need | Use |
|------|-----|
| Several entries keyed by ID, each independently expirable | `Cache` |
| One computed result for the whole service (a summary, a snapshot, a loaded config) | `CachedValue` |
| A large dataset that would be wasteful to copy onto every instance | `IMap` — see [`clustering.md`](./clustering.md) |
| Instance-local scratch data, no cluster involvement | `Cache` with `replicate: false` |
| A distributed set of unique items | `ISet` — see [`clustering.md`](./clustering.md) |

`Cache` and `CachedValue` are *fully replicated* when `replicate: true` — every instance holds a
complete copy. That is what makes reads fast and local, and it is also why they are a poor fit for
large data. `IMap` partitions entries across members instead, trading local-read speed for capacity.

## Cache

Create a `Cache` in `init()` via `createCache()`:

```groovy
class PositionService extends BaseService {

    private Cache<String, List<Position>> positionCache

    void init() {
        positionCache = createCache(
            name: 'positions',
            expireTime: 5 * MINUTES,     // entries expire after this duration
            replicate: true,             // share across cluster (default: false)
            serializeOldValue: false     // performance optimization for large values
        )
    }

    List<Position> getPositions(String fundId) {
        positionCache.getOrCreate(fundId) {
            loadPositionsFromDb(fundId)
        }
    }
}
```

Constructor parameters:

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `name` | `String` | required | Unique name within the service |
| `expireTime` | `Long` or `Closure` | `null` | TTL in ms, or closure returning ms. If null, entries never expire |
| `expireFn` | `Closure<Boolean>` | `null` | Custom expiration test `{ CacheEntry -> Boolean }`. Takes precedence over `expireTime` |
| `timestampFn` | `Closure` | `null` | Custom timestamp `{ V -> Long\|Date\|Instant }`. Defaults to entry creation time |
| `replicate` | `Boolean` | `false` | Share across cluster via Hazelcast `ReplicatedMap` |
| `serializeOldValue` | `Boolean` | `false` | Include old values in `CacheEntryChanged` events. Disable for large objects |
| `onChange` | `Closure` | `null` | Handler `{ CacheEntryChanged -> void }` called on entry changes. See [Change Handlers](#change-handlers-onchange) |

Key `Cache` API methods:

| Method | Description |
|--------|-------------|
| `get(key)` | Get value at key, or null. Removes and returns null if the entry has expired |
| `getEntry(key)` | Get `CacheEntry` at key (includes metadata), or null |
| `getOrCreate(key, Closure)` | Get value, creating it via the closure if absent or expired. The key is passed to the closure |
| `put(key, value)` | Set entry. Passing `null` **removes** the entry |
| `remove(key)` | Remove entry (equivalent to `put(key, null)`) |
| `clear()` | Clear all entries, removing key-wise so each removal fires a change event |
| `getMap()` | Get a `Map<K, V>` snapshot of all current entries. **Culls expired entries as a side effect** |
| `getTimestamp(key)` | Get the timestamp of the entry at key |
| `size()` | Number of entries. May over-report — expired entries are counted until culled |
| `ensureAvailable(key, ...)` | Block until an entry exists at key. See [Waiting for Values](#waiting-for-values-ensureavailable) |
| `asBoolean()` | Groovy truthiness — `if (cache)` tests **non-empty**, not non-null |

### Expiration and Culling

Expiry is opt-in. With neither `expireTime` nor `expireFn` set, entries live until explicitly
removed or the service is destroyed.

- **`expireTime`** — a TTL in milliseconds, or a closure returning one. A closure is re-evaluated on
  each expiry check, so the TTL can track a soft config value that changes at runtime.
- **`expireFn`** — a predicate `{ CacheEntry -> Boolean }` for expiry rules that aren't a simple
  age check. When provided it takes precedence and `expireTime` is ignored.
- **`timestampFn`** — determines what "age" means. By default an entry's age is measured from when it
  was cached; supply `timestampFn` to measure from a timestamp carried inside the value instead
  (useful when caching data that was already stale on arrival).

Expired entries are removed by two mechanisms:

1. **Lazily on access** — `get()` and `getEntry()` check expiry and remove the entry before
   returning null.
2. **By a cull timer** — an internal `cullEntries` timer sweeps the whole cache every 15 minutes. On
   a replicated cache the timer is `primaryOnly`, so the sweep runs once cluster-wide.

Because culling is periodic rather than immediate, an expired entry can still occupy memory and be
counted by `size()` for up to 15 minutes after it expires. Code that needs an accurate count should
call `getMap()`, which culls first.

## CachedValue

`CachedValue<V>` holds a single value — ideal for an expensive computation that should be shared
across the cluster: compute once on the primary, replicate to all.

```groovy
class MarketDataService extends BaseService {

    private CachedValue<Map> summary

    void init() {
        summary = createCachedValue(
            name: 'summary',
            replicate: true,
            expireTime: 30 * MINUTES
        )
    }

    Map getSummary() {
        summary.getOrCreate {
            computeExpensiveSummary()
        }
    }
}
```

Constructor parameters: `name`, `expireTime`, `expireFn`, `timestampFn`, `replicate`, and
`onChange`. These are named and typed as their `Cache` counterparts, and the age comparison behind
`expireTime` is the same (an entry is expired once `now > timestamp + expireTime`), but the two
classes are not interchangeable. The differences are worth knowing before assuming behavior carries
over.

### Differences from `Cache`

| | `Cache` | `CachedValue` |
|---|---|---|
| `serializeOldValue` | Supported, defaults to `false` | Not supported. `oldValue` is therefore always available on change events |
| A null value | Cannot be held. `put(key, null)` removes the entry | Held as "unset". Expiry checks short-circuit, so a null value never expires |
| `expireTime: 0` | Honored, expiring entries immediately | Silently ignored. The check is a Groovy truth test, and `0` is falsy |
| Sweeping expired data | A `cullEntries` timer sweeps every 15 minutes | No sweep. Expiry is evaluated only on access |
| Replication transport | Hazelcast `ReplicatedMap`, per entry | Hazelcast `ReliableTopic`, whole value, with replay to joining members |
| `onChange` dispatch | Synchronous when not clustered | Always asynchronous. See [Change Handlers](#change-handlers-onchange) |

Otherwise the expiry options behave as described in
[Expiration and Culling](#expiration-and-culling): `expireFn` takes precedence over `expireTime`,
and `timestampFn` determines what an entry's age is measured from.

Key `CachedValue` API methods:

| Method | Description |
|--------|-------------|
| `get()` | Get the value, or null. Clears and returns null if expired |
| `getOrCreate(Closure)` | Get, computing via the closure if absent or expired |
| `set(value)` | Set the value, publishing to the cluster when replicated |
| `clear()` | Clear the value (equivalent to `set(null)`) |
| `getTimestamp()` | Get the timestamp of the current entry |
| `ensureAvailable(...)` | Block until a value is populated. See [Waiting for Values](#waiting-for-values-ensureavailable) |
| `asBoolean()` | Groovy truthiness — `if (cachedValue)` tests that the value is non-null |

## Change Handlers (`onChange`)

Both `Cache` and `CachedValue` accept an `onChange` closure at construction, and additional
handlers may be registered later via `addChangeHandler()`. Handlers receive a
`CacheEntryChanged<K, V>` / `CachedValueChanged<V>` describing the `source`, `key`, `oldValue`,
and `value`.

**Handlers are asynchronous in every case except one: a `Cache` that is not clustered.** This is
the single most important thing to know about them — treat asynchronous delivery as the rule and
write handlers accordingly.

| Object | Mode | Delivery | Thread |
|--------|------|----------|--------|
| `Cache` | not clustered | **Synchronous** — completes before `put()` returns | Calling thread |
| `Cache` | clustered | Asynchronous | Hazelcast event thread |
| `CachedValue` | either | Asynchronous | Grails `Promise` (`task {}`) worker |

"Clustered" here means `replicate: true` **and** `multiInstanceEnabled` — not `replicate` alone.
Because `multiInstanceEnabled` defaults to `true`, a `replicate: true` cache is normally on the
asynchronous path even when only one instance is running. But an environment that explicitly sets
the `multiInstanceEnabled` instance config to `false` (a common dev-environment choice) flips that
same cache to synchronous delivery. **Identical application code can therefore deliver events
synchronously in development and asynchronously in production.** Never let correctness depend on
which path is active.

For a clustered `Cache`, `put()` does not invoke handlers at all — delivery is driven entirely by a
Hazelcast `ReplicatedMap` entry listener, which fires on *every* instance including the one that
made the change. Consequences:

- The handler runs after `put()` has already returned.
- Exceptions thrown by the handler do not propagate to the caller of `put()`; they surface in
  Hazelcast's listener plumbing instead. Handle errors inside the handler.
- The handler runs once per instance, so N instances means N executions. See
  [`clustering.md`](./clustering.md) and [`websocket.md`](./websocket.md) for why this matters when
  broadcasting to clients.

```groovy
// ❌ Wrong — assumes the handler has already run once put() returns.
// Holds only for a non-clustered Cache; races everywhere else.
cache.put('key', value)
doSomethingThatNeedsHandlerSideEffects()

// ✅ Correct — do the follow-on work inside the handler itself.
cache = createCache(
    name: 'positions',
    replicate: true,
    onChange: { CacheEntryChanged change ->
        doSomethingThatNeedsHandlerSideEffects()
    }
)
```

To observe a value's arrival from another instance rather than reacting to a change, prefer
`ensureAvailable()` — it blocks with an explicit timeout instead of relying on delivery timing.

**Accessing `oldValue`.** `Cache` defaults to `serializeOldValue: false`, which suppresses
`oldValue` — `CacheEntryChanged.getOldValue()` returns `null` and logs a warning. This applies to
non-clustered caches too, even though no serialization is involved there. Set
`serializeOldValue: true` if a handler genuinely needs the previous value, and expect the added
serialization cost on every change. `CachedValue` has no such option; its `oldValue` is always
available.

## Waiting for Values: `ensureAvailable()`

Both structures provide `ensureAvailable()`, which blocks until a value is present or a timeout
elapses. This matters during startup on a non-primary instance, which may need a replicated value
that only the primary populates:

```groovy
void init() {
    marketData = createCachedValue(name: 'marketData', replicate: true)
    marketData.ensureAvailable(timeout: 60 * SECONDS)
}
```

| Parameter | Default | Description |
|-----------|---------|-------------|
| `timeout` | `30 * SECONDS` | Time in ms to wait. `-1` waits indefinitely (not recommended) |
| `interval` | `1 * SECONDS` | Time in ms between checks |
| `timeoutMessage` | `null` | Custom message for the thrown exception |

On timeout a `TimeoutException` is thrown. `Cache.ensureAvailable()` additionally takes the `key` to
watch as its first argument.

Prefer `ensureAvailable()` over an `onChange` handler when the goal is simply to wait for data — it
is explicit about the timeout and does not depend on event-delivery timing. Note that it blocks the
calling thread, so a long timeout in `init()` will hold up service initialization, which is itself
bounded (30 seconds by default). See [`base-classes.md`](./base-classes.md) on service lifecycle.

## Replication

Setting `replicate: true` shares a cache across the cluster, but the two structures achieve it
differently:

| | Backing when clustered | Backing when not clustered |
|---|---|---|
| `Cache` | Hazelcast `ReplicatedMap` | `ConcurrentHashMap` |
| `CachedValue` | Hazelcast `ReliableTopic` | Plain field |

Hazelcast resource names follow the standard Hoist pattern:

- `Cache` → `xhcache.{FullClassName}[{name}]`
- `CachedValue` → `xhcachedvalue.{FullClassName}[{name}]`

`CachedValue`'s `ReliableTopic` replays the most recent value to instances joining later, so a new
member picks up the current value without waiting for the next write.

Two consequences worth internalizing:

- **Replication is eventually consistent.** After a write on one instance there is a brief window
  where others still see the old value. Don't build logic that assumes instant cluster-wide
  agreement.
- **`replicate: true` alone does not mean "clustered."** The effective switch is
  `replicate && multiInstanceEnabled`. This affects change-handler threading in particular — see
  [Change Handlers](#change-handlers-onchange).

Values stored in a replicated structure must be serializable. Hoist configures Kryo as Hazelcast's
global serializer, which handles Maps, Lists, and simple POGOs, but **not** GORM domain objects or
closures. See [`clustering.md`](./clustering.md) for the full serialization discussion.

## Clearing and Invalidation

`BaseService.clearCaches()` does **not** automatically clear caches created by `createCache()` or
`createCachedValue()` — the base implementation only records a `lastCachesCleared` timestamp.
Override it and clear each cache explicitly:

```groovy
void clearCaches() {
    super.clearCaches()
    positionCache.clear()
}
```

Always call `super.clearCaches()`. To invalidate automatically when a soft config changes, declare
`clearCachesConfigs` on the service rather than wiring up a subscription by hand — see
[`base-classes.md`](./base-classes.md) and [`configuration.md`](./configuration.md).

When a cache is populated by a timer, prefer re-running the timer over leaving the cache empty until
its next scheduled execution:

```groovy
void clearCaches() {
    super.clearCaches()
    // Repopulate immediately via the existing timer — no risk of overlapping runs.
    refreshTimer.forceRun()
}
```

`Cache.clear()` removes entries one key at a time rather than in bulk. This is deliberate: it
guarantees a change event per removed entry, and avoids errors from calling `clear()` on a Hazelcast
`ReplicatedMap` directly.

## Admin Console

`Cache` and `CachedValue` both implement `AdminStats`, and reporting is **automatic** as long as the
object was created through `createCache()` / `createCachedValue()`. There is no need to surface cache
stats from the owning service's own `getAdminStats()`.

The mechanism is worth understanding, because it depends entirely on that factory registration:

1. The factory methods call `BaseService.addResource()`, which stores the object in the service's
   `resources` map under the name you supplied.
2. `ServiceManagerService` walks `resources` when building the **Cluster > Services** tab and, for
   each entry implementing `AdminStats`, nests its stats under a `resources` key alongside the
   service's own stats. Resources whose names begin with `xh_` are skipped as framework internals.
3. `ClusterObjectsService` walks `resources` again for the **Cluster > Objects** tab, listing each
   cache as its own object named `{FullClassName}[{name}]`.

A cache constructed directly rather than through the factory is in no service's `resources` map, so
it is invisible to both tabs. This is one of the concrete reasons the factory methods are the only
supported way to create these objects.

Stats reported per class:

- `Cache` — name, type, `replicate`, entry count, latest entry timestamp, last cull time.
- `CachedValue` — name, type, `replicate`, timestamp, and size when the value is a Collection or Map.

Replicated structures also return a non-empty `getComparableAdminStats()`, naming the stats that
should agree across instances: `count` and `latestTimestamp` for `Cache`, and `timestamp` for
`CachedValue` plus `size` when the value is a Collection or Map.
`ClusterObjectsReport` compares those keys across members and flags divergence.
Non-replicated caches return an empty list, since per-instance differences are expected.

What services *do* commonly add to their own `getAdminStats()` is **derived** data rather than cache
mechanics: a count, or the cached value itself. `DefaultRoleService` reports role-assignment counts
drawn from its cached values, and `AlertBannerService` reports the current banner. The cache's own
name, size, and timestamps arrive automatically and need no help.

Each structure also gets its own logger, namespaced within the owning service:
`{ServiceLoggerName}.Cache[{name}]` and `{ServiceLoggerName}.CachedValue[{name}]`. This allows
fine-grained log configuration for a single cache — useful for tracing cull activity or
serialization timing without turning up logging for the whole service. See
[`logging.md`](./logging.md).

## Common Patterns

### The `getOrCreate` Pattern

The `getOrCreate` method on both `Cache` and `CachedValue` is a go-to pattern for lazily computing
and caching expensive results. The closure runs only when the value is absent or expired, and the
result is cached for subsequent calls:

```groovy
class CompanyService extends BaseService {

    private Cache<String, Map> companyCache

    void init() {
        companyCache = createCache(
            name: 'companies',
            expireTime: 30 * MINUTES,
            replicate: true
        )
    }

    /** Returns company data, loading from the database only on cache miss. */
    Map getCompany(String ticker) {
        companyCache.getOrCreate(ticker) {
            // This closure runs only when the entry is absent or expired.
            // The key is passed as the closure argument.
            loadCompanyFromDb(ticker)
        }
    }
}
```

For single-value caches, `CachedValue.getOrCreate` works the same way without a key:

```groovy
private CachedValue<List<Map>> allCompanies

Map getSummary() {
    allCompanies.getOrCreate {
        computeExpensiveSummary()
    }
}
```

### Timer-driven Cache Refresh

A common pattern combines a timer with a replicated cache for periodically refreshed data. The
primary instance fetches data on a timer and the cache replicates it to all instances:

```groovy
class MarketDataService extends BaseService {

    private CachedValue<Map> marketData
    private Timer refreshTimer

    void init() {
        marketData = createCachedValue(name: 'marketData', replicate: true)
        refreshTimer = createTimer(
            name: 'refreshMarketData',
            runFn: this.&refreshMarketData,
            interval: 'xhMarketDataRefreshSecs',  // interval from AppConfig
            intervalUnits: SECONDS,
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

Use `Timer.forceRun()` to trigger an immediate refresh without risk of overlapping the timer's
regular execution. See [`base-classes.md`](./base-classes.md) for the full `createTimer()` API.

## Common Pitfalls

### Relying on synchronous `onChange` delivery

Only a **non-clustered `Cache`** fires `onChange` handlers synchronously, inline on the thread that
called `put()`. A clustered `Cache` dispatches from a Hazelcast event thread, and `CachedValue`
always dispatches via a Grails `Promise` — in both cases `put()` / `set()` returns before the
handler runs. Code that writes to a cache and then immediately reads state a handler was supposed to
set will work in one configuration and race in another. Put the follow-on work inside the handler,
or use `ensureAvailable()` to wait explicitly.

### Assuming `replicate` doesn't change `onChange` timing

Toggling `replicate` silently changes change-handler threading, and the switch is
`replicate && multiInstanceEnabled` — so a `replicate: true` cache also reverts to synchronous
delivery in an environment that sets the `multiInstanceEnabled` instance config to `false`. Don't
write handlers or callers that depend on either timing. See
[Change Handlers](#change-handlers-onchange).

### Trying to cache null

Neither structure can usefully cache a null value. `Cache.put(key, null)` **removes** the entry, and
`CachedValue.set(null)` leaves the value unset. As a result, a `getOrCreate` closure that
legitimately returns null will re-run on every single call — the "miss" is never cached. If null is a
meaningful result worth caching, wrap it in a sentinel (e.g. cache an empty Map or a small holder
object) instead.

### Large objects in replicated caches

`Cache` and `CachedValue` with `replicate: true` copy data to every instance, so a large dataset
consumes memory N times over and pays serialization cost on every write. Use `IMap` for large
datasets that can be partitioned, or `replicate: false` for instance-local data. See
[`clustering.md`](./clustering.md).

### Assuming `size()` is exact

`size()` counts entries still resident in the map, including entries that have expired but not yet
been culled — and culling runs only every 15 minutes. Call `getMap()` first if you need an accurate
count, since it culls before building its snapshot.

### Treating a cache as nullable in a boolean test

Both classes override Groovy truthiness. `if (myCache)` tests whether a `Cache` is **non-empty**,
and whether a `CachedValue`'s value is non-null — neither tests whether the field itself was
assigned. A null check on the field must be explicit: `if (myCache != null)`.

## Client Integration

Caches have no direct client-side counterpart, but they surface in the Admin Console's Services tab
via the admin stats described above, where an admin can inspect entry counts and timestamps and
trigger `clearCaches()` on a service.

When a cache change should be pushed to connected clients, note that a replicated cache's
`onChange` handler fires on *every* instance — use `pushToLocalChannels()` rather than
`pushToAllChannels()` to avoid sending N copies. See [`websocket.md`](./websocket.md).
