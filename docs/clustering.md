> **Status: DRAFT** — This document is awaiting review. Content may be incomplete or subject to
> change. Do not remove this banner until the document has been interactively reviewed and approved.

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
(`createCache`, `createCachedValue`, `createTimer`, `createIMap`, `createISet`). Direct interaction with
`ClusterService` is rarely needed in application code.

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

### Primary Instance

The **primary instance** is the oldest member of the Hazelcast cluster. It handles tasks that
should run on only one instance — typically scheduled data refreshes, cleanup jobs, and
monitoring checks. When the primary instance leaves the cluster, the next-oldest member
automatically becomes the new primary.

```groovy
// Check if this instance is primary
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
| `localName` | Human-readable name for this instance |
| `hzInstance` | Direct access to the Hazelcast instance (rarely needed) |
| `ensureRunning()` | Throws if instance is not in `RUNNING` state |

### ClusterConfig

Configures the Hazelcast instance before it starts. Handles:

- **Network discovery** — `createNetworkConfig()` is a no-op by default (Hazelcast uses multicast discovery); apps can override to customize
- **Hibernate cache regions** — GORM second-level cache backed by Hazelcast JCache
- **Default eviction policies** — LRU eviction for Hibernate cache regions
- **Application customization** — Services can provide a `static configureCluster` closure

#### Custom Hazelcast Configuration

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

### Distributed Data Structures

All distributed data structures are created through `BaseService` factory methods (see
[`base-classes.md`](./base-classes.md) for the full API). Here we focus on the clustering aspects.

#### Cache (ReplicatedMap-backed)

`Cache<K, V>` uses a Hazelcast `ReplicatedMap` when `replicate: true`, meaning every instance
holds a complete copy of all entries. This is ideal for small-to-medium datasets that are read
frequently. The default is `replicate: false` (local-only, backed by a `ConcurrentHashMap`).

```groovy
private Cache<String, Map> priceCache

void init() {
    priceCache = createCache(
        name: 'prices',
        replicate: true,          // backed by Hazelcast ReplicatedMap
        expireTime: 5 * MINUTES   // entries expire after this duration
    )
}
```

Hazelcast resource name: `xhcache.{FullClassName}[prices]`

When `replicate: false`, the cache uses a local `ConcurrentHashMap` instead — useful for
instance-specific data that doesn't need to be shared.

Cache entries have a configurable `expireTime` and are culled by an internal timer. Expired entries
are removed lazily on access and periodically by the cull timer.

#### CachedValue (ReliableTopic-backed)

`CachedValue<T>` stores a single value that is replicated across the cluster via a Hazelcast
`ReliableTopic`. When a value is set on any instance, all other instances receive the update.

```groovy
private CachedValue<Map> summary

void init() {
    summary = createCachedValue(
        name: 'summary',
        replicate: true,          // backed by Hazelcast ReliableTopic
        expireTime: 30 * MINUTES
    )
}
```

Hazelcast resource name: `xhcachedvalue.{FullClassName}[summary]`

The `ReliableTopic` ensures that new instances joining the cluster receive the most recent value
(via replay of the last message). This makes `CachedValue` ideal for expensive computations that
should be shared — compute once on the primary, replicate to all.

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

#### ReplicatedMap

`ReplicatedMap<K, V>` is a Hazelcast map where every instance holds a complete copy (eventually
consistent). This is what `Cache` uses internally — use `createReplicatedMap()` directly only
when you need raw Hazelcast map access without `Cache`'s expiration and admin features.

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

## Configuration

### Hazelcast Network Configuration

Hazelcast uses its default multicast discovery out of the box. The `ClusterConfig.createNetworkConfig()`
method has an empty body by default — it serves as a hook that applications can override in a
subclass to customize network discovery (e.g., TCP/IP member lists, cloud discovery) for their
deployment environment.

### Hibernate Cache Regions

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

`Cache` (with `replicate: true`) and `CachedValue` (with `replicate: true`) replicate data to
every instance. Storing large datasets (e.g., millions of rows) in these structures will consume
memory on every instance. Use `IMap` for large datasets that can be partitioned, or `Cache` with
`replicate: false` for instance-local data.

### Forgetting `primaryOnly` on scheduled tasks

Without `primaryOnly: true`, a timer runs on every instance in the cluster. This means scheduled
database cleanups, email sends, or API calls will execute N times (once per instance). Always use
`primaryOnly: true` for tasks that should run once cluster-wide.

### Non-serializable values in distributed structures

All values stored in Hazelcast distributed objects must be serializable (Java `Serializable` or
Hazelcast `DataSerializable`). Storing Grails domain objects, closures, or other non-serializable
objects will throw `HazelcastSerializationException`. Use Maps, Lists, or simple POGOs instead.

### Assuming immediate replication

Hazelcast replication is eventually consistent. After setting a value on one instance, there may
be a brief window where other instances see the old value. For most Hoist use cases this is
acceptable, but don't rely on instant cross-instance consistency for critical operations.
