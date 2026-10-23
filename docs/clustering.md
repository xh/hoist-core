# Clustering

## Overview

Hoist uses Hazelcast to coordinate multiple application instances running as a cluster. The
clustering system provides distributed data structures (caches, maps, topics), primary instance
coordination, and pub/sub messaging — all managed through `BaseService` factory methods and
`ClusterService`.

Clustering enables:
- **Shared state** — Caches and cached values replicated across instances
- **Primary-only tasks** — Timers that run on only one instance (e.g., scheduled jobs)
- **Pub/sub messaging** — Cluster-wide event distribution via Hazelcast topics
- **Distributed execution** — Running code on specific or all instances
- **Admin visibility** — Cluster-wide monitoring via the Admin Console

Most of the clustering functionality is accessed indirectly through `BaseService` resource factories
(`createCache`, `createCachedValue`, `createTimer`, `createIMap`, `createISet`). `ClusterUtils`
provides methods for distributed execution — `runOnInstance()`, `runOnPrimary()`, and
`runOnAllInstances()` — to run service methods on specific or all cluster members. Direct
interaction with `ClusterService` is rarely needed in application code.

## Source Files

| File | Location | Role |
|------|----------|------|
| `ClusterService` | `grails-app/services/io/xh/hoist/cluster/` | Primary service — cluster state, primary detection |
| `ClusterConfig` | `grails-app/init/io/xh/hoist/` | Hazelcast configuration |
| `Cache` | `src/main/groovy/io/xh/hoist/cache/` | Key-value cache (Hazelcast `ReplicatedMap` backed) |
| `CachedValue` | `src/main/groovy/io/xh/hoist/cachedvalue/` | Single-value cache (Hazelcast `ReliableTopic` backed) |
| `Timer` | `src/main/groovy/io/xh/hoist/util/` | Managed polling timer with `primaryOnly` support |
| `HoistCoreGrailsPlugin` | `src/main/groovy/io/xh/hoist/` | Hazelcast lifecycle (init and shutdown) |

## Architecture

### Cluster Lifecycle

```
Application Start
    │
    ├── HoistCoreGrailsPlugin.doWithSpring()
    │       └── ClusterService.initializeHazelcast()    ← Hazelcast instance created
    │
    ├── ApplicationReadyEvent
    │       └── ClusterService.onApplicationEvent()     ← Sets instanceState = RUNNING
    │
    ├── Runtime
    │       └── HoistFilter → ClusterService.ensureRunning()  ← Rejects requests if not RUNNING
    │
    └── Shutdown
            ├── ClusterService.instanceState = STOPPING
            ├── Timer.shutdownAll()
            └── ClusterService.shutdownHazelcast()
```

### Instance States

| State | Description |
|-------|-------------|
| `STARTING` | Hazelcast initialized, services loading |
| `RUNNING` | Fully ready, accepting requests |
| `STOPPING` | Shutting down, rejecting new requests |

### Health Signals

`instanceState` tracks the coarse **application** lifecycle — it is set to `RUNNING` once at
startup and only leaves that state on a deliberate shutdown. It does **not** reflect the health of
the underlying Hazelcast member: an instance whose Hazelcast member has died (for example after an
`OutOfMemoryError`) can keep reading `RUNNING` while the JVM continues to serve HTTP — a "zombie"
instance.

For that case, `ClusterService.isHazelcastRunning` reports whether **this instance's own** Hazelcast
member is still live, reading the member's lifecycle state directly:

```groovy
boolean healthy = clusterService.isHazelcastRunning
```

It is cheap and non-throwing (returns `false` rather than propagating any error), making it suitable
for a high-frequency health endpoint. The value is also included in `ClusterService.getAdminStats()`.

Hoist already attempts a graceful self-heal: `ClusterService` registers a Hazelcast
`LifecycleListener` that terminates the app if the member shuts down unexpectedly. That path is
cooperative, however, and has been observed to stall under heap exhaustion — precisely the scenario
it should catch. A Kubernetes **liveness probe** wired to a status endpoint backed by
`isHazelcastRunning` provides a non-cooperative backstop: a wedged member reads as not-running, and
k8s restarts the pod via SIGKILL without needing any cooperation from the stalled JVM.

> **Caveat — pair a liveness probe with a startup probe.** `isHazelcastRunning` returns `false`
> during startup, before the Hazelcast member is up. If a liveness probe is configured without a
> Kubernetes `startupProbe` (or a sufficient `initialDelaySeconds`), k8s will kill instances
> mid-boot in a crash loop. Gate the liveness probe behind a startup probe so the instance is given
> time to initialize Hazelcast before liveness is enforced.

Note this is a strict improvement over `instanceState` and the cooperative listener, not a
universal detector: an instance can still be heap-thrashing while its Hazelcast member remains
technically active, in which case `isHazelcastRunning` reports `true`. No single cheap flag can
catch every degraded state.

### Primary Instance

The **primary instance** is the oldest member of the Hazelcast cluster. It handles tasks that
should run on only one instance — typically scheduled data refreshes, batch jobs, and
monitoring checks. When the primary instance leaves the cluster, the next-oldest member
automatically becomes the new primary.

The `isPrimary` property is on `ClusterService` and is also available directly on `BaseService`
for convenience:

```groovy
// In any BaseService
if (isPrimary) {
    // Only executes on the primary instance
}
```

## Key Classes

### ClusterService

Manages the Hazelcast cluster lifecycle and provides cluster-awareness to the rest of the
framework.

| Property/Method | Description |
|-----------------|-------------|
| `isPrimary` | `true` if this is the oldest cluster member |
| `instanceState` | Current instance state (`STARTING`, `RUNNING`, `STOPPING`) |
| `isHazelcastRunning` | `true` if this instance's local Hazelcast member is live (see [Health Signals](#health-signals)) |
| `localName` | Human-readable name for this instance |
| `hzInstance` | Direct access to the Hazelcast instance (rarely needed) |
| `ensureRunning()` | Throws if instance is not in `RUNNING` state |

### Distributed Data Structures

All distributed data structures are created through `BaseService` factory methods (see
[`base-classes.md`](./base-classes.md) for the full API). Here we focus on the clustering aspects.

#### Cache and CachedValue

`Cache<K, V>` and `CachedValue<V>` are Hoist's managed caching structures, both created through
`BaseService` and both replicable across the cluster. They are documented in full in
[`caching.md`](./caching.md) — covered here is only how they map onto Hazelcast.

| | Backing when `replicate: true` | Backing otherwise | Hazelcast resource name |
|---|---|---|---|
| `Cache` | `ReplicatedMap` | `ConcurrentHashMap` | `xhcache.{FullClassName}[{name}]` |
| `CachedValue` | `ReliableTopic` | Plain field | `xhcachedvalue.{FullClassName}[{name}]` |

Both are *fully replicated* — every instance holds a complete copy, which is what makes reads local
and fast, and why they suit small-to-medium datasets rather than large ones. `CachedValue`'s
`ReliableTopic` replays the most recent value to instances joining later, so a new member picks up
the current value without waiting for the next write.

Two cluster-specific behaviors to be aware of:

- **`replicate: true` is not sufficient on its own.** The effective switch is
  `replicate && multiInstanceEnabled`. An environment that disables `multiInstanceEnabled` runs
  these structures in local mode regardless of the `replicate` setting.
- **Replication changes `onChange` handler threading.** A replicated `Cache` dispatches handlers
  asynchronously from a Hazelcast entry listener, on every instance; a non-replicated one dispatches
  synchronously inside `put()`. See
  [Change Handlers](./caching.md#change-handlers-onchange) for the full contract.

`ensureAvailable()` on either structure blocks until a value is present (default 30s timeout) —
useful during startup when a non-primary instance needs a value only the primary populates:

```groovy
void init() {
    marketData = createCachedValue(name: 'marketData', replicate: true)
    marketData.ensureAvailable(timeout: 60 * SECONDS)
}
```

#### ReplicatedMap

`ReplicatedMap<K, V>` is a Hazelcast map where every instance holds a complete copy (eventually
consistent). This is what `Cache` uses internally — use `createReplicatedMap()` directly only
when you need raw Hazelcast map access without `Cache`'s expiration and admin features.


#### IMap (Distributed Partitioned Map)

`IMap<K, V>` is a Hazelcast distributed map where data is partitioned across cluster members.
Unlike `Cache` (fully replicated), `IMap` distributes entries — each entry lives on a subset of
instances. This is better for large datasets.

```groovy
private IMap<String, byte[]> documentStore

void init() {
    documentStore = createIMap('documentStore')
}
```

Hazelcast resource name: `{FullClassName}[documentStore]`

#### Timer (Cluster-aware)

`Timer` supports a `primaryOnly` mode where the task runs only on the primary instance. This
prevents duplicate work across cluster members:

```groovy
createTimer(
    name: 'dailyCleanup',
    runFn: this.&cleanup,
    interval: 24 * HOURS,
    primaryOnly: true          // runs only on the primary instance
)
```

When `primaryOnly: true`, the timer uses a Hazelcast `ReplicatedMap`
(`xhTimersLastCompleted`) to track the last completion time across the cluster. This ensures
that if the primary changes (e.g., old primary shuts down), the new primary knows when the task
last ran and can schedule the next run correctly.

### Naming Convention

All Hazelcast distributed objects follow a naming pattern that groups resources by service:

```
{FullClassName}[{resourceName}]
```

For example, `io.xh.hoist.config.ConfigService[configs]`.

The `Cache` and `CachedValue` wrappers add their own prefixes:
- Cache: `xhcache.{FullClassName}[{resourceName}]`
- CachedValue: `xhcachedvalue.{FullClassName}[{resourceName}]`

This convention enables the Admin Console's "Cluster Objects" view to group and display all
distributed resources by service.

### Pub/Sub via Topics

Hazelcast topics provide cluster-wide pub/sub messaging. Services subscribe via
`BaseService.subscribeToTopic()`:

```groovy
void init() {
    // Subscribe to a cluster-wide topic
    subscribeToTopic(
        topic: 'xhConfigChanged',
        onMessage: { Map msg -> handleConfigChange(msg) },
        primaryOnly: false      // receive on all instances
    )
}

// Publish to a topic
getTopic('myCustomTopic').publish([action: 'refresh', source: username])
```

Topics are used extensively within hoist-core for config changes, preference changes, and other
framework events.

### ClusterConfig

Configures the Hazelcast instance before it starts. Handles:

- **Network discovery** — `createNetworkConfig()` is a no-op by default (Hazelcast uses multicast discovery); apps can override to customize
- **Hibernate cache regions** — GORM second-level cache backed by Hazelcast JCache
- **Default eviction policies** — LRU eviction for Hibernate cache regions
- **Application customization** — Services can provide a `static configureCluster` closure

## Configuration

### Hazelcast Network Configuration

Hazelcast uses its default multicast discovery out of the box. The `ClusterConfig.createNetworkConfig()`
method has an empty body by default — it serves as a hook that applications can override in a
subclass to customize network discovery (e.g., TCP/IP member lists, cloud discovery) for their
deployment environment.

### Customizing Hibernate Cache Regions

`ClusterConfig` configures Hazelcast as the GORM second-level cache provider with default
eviction policies:

- Default cache: 5000 entries, LRU eviction
- Update timestamps region: 1000 entries
- Query results region: 10000 entries

Domain classes can customize their cache settings via a `static cache` closure:

```groovy
class MyDomain {
    static mapping = {
        cache true
    }

    // Optional: customize the Hazelcast cache config for this domain
    static cache = { cfg ->
        cfg.evictionConfig.size = 10000
    }
}
```

### Customizing Distributed Resources

Services can customize Hazelcast configuration for their distributed resources by declaring a
`static configureCluster` closure:

```groovy
class MyService extends BaseService {

    static configureCluster = { Config c ->
        c.getMapConfig(hzName('largeDataset', this)).with {
            evictionConfig.size = 100
        }
    }

    private IMap<String, Map> largeDataset = createIMap('largeDataset')
}
```

## Common Patterns

### Primary-only Data Refresh

The most common clustering pattern — a timer on the primary instance refreshes a cached value
that replicates to all instances:

```groovy
class MarketDataService extends BaseService {

    private CachedValue<Map> marketData

    void init() {
        marketData = createCachedValue(name: 'marketData', replicate: true)
        createTimer(
            name: 'refreshMarketData',
            runFn: this.&refreshMarketData,
            interval: 'xhMarketDataRefreshSecs',
            primaryOnly: true
        )
    }

    Map getMarketData() { marketData.get() }

    private void refreshMarketData() {
        marketData.set(fetchFromExternalApi())
    }
}
```

### Cluster-wide Event Broadcasting

Publish a message that all instances will receive:

```groovy
// Publisher
getTopic('dataRefreshed').publish([source: 'MarketDataService', timestamp: new Date()])

// Subscriber (in a different service)
void init() {
    subscribeToTopic(
        topic: 'dataRefreshed',
        onMessage: { Map msg -> clearLocalState() }
    )
}
```

## Client Integration

The Admin Console provides several cluster-related views:

- **Cluster > Instances** — Lists all cluster members with their state, uptime, and memory usage
- **Cluster > Objects** — Shows all distributed Hazelcast objects with sizes and stats
- **Cluster > Services** — Admin stats from all services across all instances

These views rely on `ClusterService.getAdminStats()` and distributed execution to gather data
from all cluster members.

## Common Pitfalls

### Large objects in replicated caches

`Cache` and `CachedValue` with `replicate: true` copy data to every instance, so a large dataset
(e.g. millions of rows) consumes memory N times over. Use `IMap` for large datasets that can be
partitioned, or `replicate: false` for instance-local data. See [`caching.md`](./caching.md) for
the other cache-specific pitfalls, including how `replicate` affects change-handler timing.

### Forgetting `primaryOnly` on scheduled tasks

Without `primaryOnly: true`, a timer runs on every instance in the cluster. This means scheduled
database cleanups, email sends, or API calls will execute N times (once per instance). Always use
`primaryOnly: true` for tasks that should run once cluster-wide.

### Non-serializable values in distributed structures

All values stored in Hazelcast distributed objects must be serializable. Hoist configures Kryo as
Hazelcast's global serializer, which handles most common types (Maps, Lists, simple POGOs)
automatically. However, Grails domain objects, closures, and other complex types may not be
Kryo-serializable and will cause errors. Favor plain Maps, Lists, or simple POGOs instead.

### Assuming immediate replication

Hazelcast replication is eventually consistent. After setting a value on one instance, there may
be a brief window where other instances see the old value. For most Hoist use cases this is
acceptable, but don't rely on instant cross-instance consistency for critical operations.
