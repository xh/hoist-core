# Coding Conventions

## Overview

This document catalogs the coding conventions used throughout hoist-core, both within the
framework's own source and in the application code that depends on it. It is the authoritative
standards reference for AI coding assistants generating server-side Hoist code and for developers
contributing to the library or building Hoist applications.

The conventions here reflect established patterns in the codebase and across XH-built applications.
They should be followed for consistency, but are not all mechanically enforced — many are caught
only in code review. Rules that the framework actively enforces at runtime (e.g. mandatory access
annotations on every controller endpoint) are called out where they apply.

This is a paired sibling to the
hoist-react [Coding Conventions](https://github.com/xh/hoist-react/blob/develop/docs/coding-conventions.md)
document. Where the two stacks share a concept (naming with the `xh` prefix, `withInfo`/`withDebug`
timed wrappers, error-handling philosophy), the conventions intentionally rhyme. Where the
technology stack genuinely differs (Groovy vs. TypeScript, Grails services vs. MobX models,
Hazelcast vs. browser-only), the conventions diverge accordingly.

## Principles

These higher-level principles guide coding decisions across the codebase. The specific conventions
in later sections are expressions of these values.

### Don't Repeat Yourself

Extract shared logic rather than duplicating it. When the same pattern appears in multiple services,
factor it into a utility class, a `BaseService` helper method, or a trait. That said, balance DRY
against readability — three similar lines of code can be clearer than a premature abstraction.
Extract when there is a genuine, stable pattern, not when two blocks of code happen to look alike
today.

Hoist's own base classes already absorb a great deal of boilerplate. Before writing a new helper,
check whether `BaseService`, `BaseController`, `LogSupport`, `IdentitySupport`, or one of the
`Utils` classes already provides what you need.

### Clear, Descriptive Naming

Choose variable, method, and class names that are clear and descriptive without being verbose. Names
should convey intent and read naturally:

```groovy
// Do: clear and descriptive
def selectedConfig = configService.getMap('myAppConfig')
boolean isEditable = !record.locked && record.status == 'DRAFT'

// Don't: too terse
def c = configService.getMap('myAppConfig')
boolean e = !record.locked && record.status == 'DRAFT'

// Don't: overly verbose
def theCurrentlySelectedConfigurationEntryFromTheDatabase = configService.getMap('myAppConfig')
```

### Keep Code Concise

Favor direct, compact expression over verbose or ceremonial patterns. Hoist's own utilities (
`configService` typed getters, `createCache`/`createTimer`, `withInfo`/`withDebug`, `renderJSON`,
`parseRequestJSON`) exist to reduce boilerplate — use them. When Groovy provides a clean idiom,
prefer that over Java-style ceremony.

### Prefer Groovy Idioms

This is a Groovy 4 codebase. Embrace Groovy's expressive features rather than writing Java with
`.groovy` extensions:

- Map and list literals: `[a: 1, b: 2]`, `[1, 2, 3]`
- GStrings: `"User ${user.username} logged in at ${new Date()}"`
- Closures and method-reference-like blocks
- Safe navigation: `user?.username`
- Spread operators and Elvis: `value ?: defaultValue`
- `with {}` blocks for property-rich initialization
- Null as the conventional "no value" sentinel
- Type inference where the type is already obvious

Avoid superfluous type declarations on local variables when Groovy can infer them clearly. Do
declare types on method signatures, public properties, and anything that forms part of an API
contract.

### Prefer Groovy Collection Methods

Use `.collect()`, `.findAll()`, `.find()`, `.groupBy()`, `.collectEntries()`, `.sum()`, `.unique()`,
`.any()`, `.every()` etc. for collection operations. They are null-safe on element access and far
more expressive than manual loops:

```groovy
// Do: idiomatic Groovy
def activeUsernames = users.findAll { it.active }*.username
def usersByDept = users.groupBy { it.department }
def totalShares = positions.sum { it.shares } ?: 0

// Don't: imperative loops where a collection method reads more cleanly
def activeUsernames = []
for (user in users) {
    if (user.active) activeUsernames.add(user.username)
}
```

## Naming

### `xh` Prefix for Framework-Owned Identifiers

Hoist reserves the `xh` prefix (lowercase) for framework-level identifiers. Application code must
not introduce new identifiers in this namespace, and framework code must use it consistently.

| Identifier kind                             | Convention                         | Examples                                                                        |
|---------------------------------------------|------------------------------------|---------------------------------------------------------------------------------|
| Event names (Grails events, cluster topics) | `xh`-prefixed camelCase            | `xhConfigChanged`, `xhTrackReceived`, `xhUserChanged`                           |
| Soft configs (`AppConfig`)                  | `xh`-prefixed camelCase            | `xhActivityTrackingConfig`, `xhEmailFilter`, `xhMonitorConfig`                  |
| User preferences                            | `xh`-prefixed camelCase            | `xhAutoRefreshEnabled`, `xhTheme`                                               |
| Built-in roles                              | `HOIST_`-prefixed UPPER_SNAKE_CASE | `HOIST_ADMIN`, `HOIST_ADMIN_READER`, `HOIST_IMPERSONATOR`, `HOIST_ROLE_MANAGER` |

Application configs, prefs, and events should use an app-specific prefix (often the lowercase app
code, e.g. `myAppPortfolioRefreshConfig`). Do not invent new `xh*` identifiers in app code.

### Service, Cache, and Timer Naming

- **Service classes**: PascalCase ending in `Service` (e.g. `PortfolioService`, `TradeService`).
  Inject as camelCase (`portfolioService`).
- **Timer names** passed to `createTimer(name: ...)`: camelCase, unique within a service (e.g.
  `refreshTimer`, `archiveTimer`).
- **Cache names** passed to `createCache(name: ...)` / `createCachedValue(name: ...)`: camelCase,
  unique within a service. The framework names the underlying Hazelcast structure as
  `{ServiceClassName}[{name}]`, so uniqueness is per-service, not global.
- **Domain classes**: PascalCase singular (e.g. `Portfolio`, `Trade`, `AppConfig`).

### Instance Config Environment Variables

`InstanceConfigUtils` reads runtime configuration from environment variables using a deterministic
key conversion. A camelCase key in code becomes `APP_{APPCODE}_{KEY_IN_UPPER_SNAKE}` as an env var:

```
appCode = 'myApp'
key 'dbUrl'             → APP_MYAPP_DB_URL
key 'sslKeystorePath'   → APP_MYAPP_SSL_KEYSTORE_PATH
```

Choose camelCase config keys that read naturally after the conversion.

## Logging and Exceptions

See [Logging](logging.md) for the full reference (configuration, layouts, custom appenders, dynamic
levels). The conventions below cover the patterns that every service and controller must follow.

### Use `LogSupport`, Not Raw SLF4J

All `BaseService` and `BaseController` instances implement the [`LogSupport`](logging.md) trait. Use
its methods rather than declaring your own SLF4J logger:

```groovy
// Do: LogSupport methods — automatically tagged with class and current user
class PortfolioService extends BaseService {
    void refresh() {
        logInfo('Refreshing portfolios')
        logDebug('Cache size before refresh:', cache.size())
    }
}

// Don't: raw SLF4J logger boilerplate
class PortfolioService extends BaseService {
    private static final Logger log = LoggerFactory.getLogger(PortfolioService)

    void refresh() {
        log.info('Refreshing portfolios')
    }
}
```

Log calls accept varargs of any type (strings, maps, exceptions) and render them as pipe-delimited
output via `LogSupportConverter`. Exceptions are formatted with a concise summary plus a stacktrace.

### Pass Maps for Structured Key-Value Data

When a log message has accompanying contextual data, **pass it as a map** rather than concatenating
into the message string. Hoist renders map entries as `key=value` pairs in the pipe-delimited
output, which keeps the log line consistent, parseable by log aggregators, and easy to filter on in
the admin console:

```groovy
// Do: structured map — each field is a separately addressable key=value pair
logInfo('Refreshing portfolios', [region: region, count: portfolios.size()])
logDebug('Cache hit', [key: ticker, ageMs: System.currentTimeMillis() - entry.dateCreated])
logError('Failed to import trades', [batchId: batch.id, source: batch.sourceSystem], e)

// Don't: string concatenation — opaque to log tooling, harder to read at scale
logInfo("Refreshing portfolios for $region (count: ${portfolios.size()})")
logError("Failed to import trades for batch ${batch.id} from ${batch.sourceSystem}", e)
```

The same map form works inside `withInfo` / `withDebug` / `withTrace` — the timing, start,
completed, and failed messages all share the structured context:

```groovy
withInfo([_msg: 'Loading portfolios', region: region, batchSize: batchSize]) {
    portfolioCache.putAll(loadFromDb(region, batchSize))
}
```

**Map key conventions:**

- **Plain keys** (`region`, `count`, `batchId`) render as `key=value` in the log line.
- **Underscore-prefixed keys** (`_msg`, `_filename`) render the value only (no `key=` prefix). Use
  these for the human-readable message and for one-off identifying values where the key would be
  noise.
- **Don't include sensitive values** (passwords, tokens, full PII). Map values are written verbatim.

Mix maps freely with strings and a trailing exception — `LogSupportConverter` flattens them all into
one structured line:

```groovy
logError('Trade rejected', [tradeId: trade.id, reason: 'INSUFFICIENT_FUNDS'], ex)
// → ... | Trade rejected | tradeId=T-42 | reason=INSUFFICIENT_FUNDS | java.lang.IllegalStateException: ...
```

### Time Blocks with `withInfo` / `withDebug`

Wrap operations whose duration matters in `withInfo`, `withDebug`, or `withTrace`. The block
automatically logs `started` (when configured), `completed | 342ms`, or `failed | 342ms` on
exception — and re-throws so behavior is unchanged:

```groovy
withInfo('Loading portfolios') {
    portfolioCache.putAll(loadFromDb())
}

def report = withDebug('Generating risk report') {
    riskService.computeReport(portfolio)
}
```

Don't reach for manual `start = System.currentTimeMillis()` / `elapsed = ...` patterns — the timed
wrappers handle this and log consistently.

**Consider tracing for important operations.** `withInfo`/`withDebug` produce log lines — fine when
all you want is human-readable timing in the log stream. For operations whose latency you actually
want to inspect in telemetry tooling (Honeycomb, Tempo, Jaeger, etc.) — external HTTP calls,
scheduled jobs, multi-step workflows, anything that might warrant a flame graph — reach for
the [tracing API](tracing.md) instead. `traceService.withSpan` (or `BaseService.observe()` via [
`ObservedRun`](tracing.md#observedrun) for combined tracing + logging + metrics) emits OpenTelemetry
spans that flow to your OTLP backend, with automatic user/request tagging and correlation across
instances:

```groovy
// Logging-only timing — local visibility via log line
withInfo('Refreshing portfolios') {
    portfolioCache.putAll(loadFromDb())
}

// Important operation — emit a span so latency is queryable in telemetry tooling
traceService.withSpan(name: 'refreshPortfolios', tags: [region: region]) { SpanRef span ->
    portfolioCache.putAll(loadFromDb(region))
}

// Best of both — `observe()` traces, logs, and counts in one call
observe('refreshPortfolios', [region: region]) {
    portfolioCache.putAll(loadFromDb(region))
}
```

Default to `withInfo`/`withDebug` for incidental timing; reach for tracing the moment "I might want
to slice this latency by tag in production" enters the picture.

### `RoutineRuntimeException` for Expected User-Facing Errors

Use `RoutineRuntimeException` (or a more specific `HttpException` subclass) for errors that are part
of normal operation — invalid input, missing entities, permission denials. Routine exceptions are
logged at DEBUG (not ERROR) and rendered to the client as 400 by default, keeping production logs
clean:

```groovy
// Do: routine exception for expected condition
if (!ticker?.matches(/[A-Z.]{1,8}/)) {
    throw new RoutineRuntimeException("Invalid ticker: $ticker")
}

// Don't: generic RuntimeException — logged at ERROR, looks like a bug
if (!ticker?.matches(/[A-Z.]{1,8}/)) {
    throw new RuntimeException("Invalid ticker: $ticker")
}
```

For specific HTTP statuses, use the dedicated subclasses: `NotAuthorizedException` (403),
`NotAuthenticatedException` (401), `NotFoundException` (404), `ValidationException` (wraps GORM
errors), `DataNotAvailableException`, `ExternalHttpException`.
See [Exception Handling](exception-handling.md) for the full hierarchy.

### Don't Try/Catch in Controller Actions

`BaseController` (via `ExceptionHandler`) catches every uncaught exception, logs it at the
appropriate level, and renders a structured JSON error to the client. Wrapping a controller action
in try/catch usually duplicates that work and risks logging or rendering inconsistently:

```groovy
// Do: let the framework handle it
@AccessRequiresRole('TRADER')
def executeTrade() {
    def args = parseRequestJSON()
    renderJSON(tradeService.execute(args))
}

// Don't: redundant catch that loses framework formatting
@AccessRequiresRole('TRADER')
def executeTrade() {
    try {
        def args = parseRequestJSON()
        renderJSON(tradeService.execute(args))
    } catch (Exception e) {
        log.error('Trade failed', e)
        render status: 500, text: e.message
    }
}
```

Catch only when you genuinely need to recover, transform, or enrich an exception before
re-throwing — not as a safety net.

## Services and Lifecycle

See [Base Classes](base-classes.md) for the full `BaseService` API. The conventions below apply on
top of that reference.

### Every Service Extends `BaseService`

All Grails services in hoist-core and Hoist applications extend [`BaseService`](base-classes.md).
This is non-negotiable — it provides the lifecycle (`init`/`destroy`/`parallelInit`), distributed
resource factories, identity access, logging, event subscriptions, and admin stats integration that
the rest of the framework expects.

```groovy
// Do
class PortfolioService extends BaseService {
    ...
}

// Don't: bare Grails service misses lifecycle, logging, identity, admin stats
class PortfolioService {
    ...
}
```

### Use Managed Resource Factories

Create caches, cached values, timers, and Hazelcast structures via the `BaseService` factory
methods — never construct them directly. The factories handle naming, registration with
`ClusterService`, lifecycle cleanup on `destroy()`, and admin-console visibility:

```groovy
// Do: managed factories
class PortfolioService extends BaseService {

    private Cache<String, Portfolio> portfolioCache
    private Timer refreshTimer

    void init() {
        portfolioCache = createCache(name: 'portfolios', expireTime: 10 * MINUTES, replicate: true)
        refreshTimer = createTimer(name: 'refreshTimer', interval: 60 * SECONDS, runFn: this.&refresh, primaryOnly: true)
    }
}

// Don't: raw Hazelcast / raw Spring scheduling — bypasses lifecycle and registry
class PortfolioService extends BaseService {
    private IMap<String, Portfolio> map = Hazelcast.getMap('portfolios')
    private ScheduledExecutorService exec = Executors.newScheduledThreadPool(1)
}
```

### `clearCaches()` Discipline

Always call `super.clearCaches()` first when overriding. The base implementation updates the
`lastCachesCleared` timestamp visible in the Admin Console — skipping it makes diagnostics
misleading. Then explicitly clear each cache the service holds:

```groovy
@Override
void clearCaches() {
    super.clearCaches()
    portfolioCache.clear()
    riskCache.clear()
    refreshTimer.forceRun()  // re-populate immediately
}
```

`clearCaches()` does **not** clear `Cache`/`CachedValue` instances automatically. Each managed
resource must be cleared explicitly.

### `clearCachesConfigs` for Soft-Config Reactivity

Declare a static `clearCachesConfigs` list of `xh`-prefixed config names to have the service's
caches cleared automatically when those configs change. The framework subscribes to
`xhConfigChanged` and invokes `clearCaches()` on matching service instances:

```groovy
class PortfolioService extends BaseService {
    static clearCachesConfigs = ['myAppPortfolioCacheConfig', 'myAppPricingSource']
    // ...
}
```

This is preferred over hand-rolled `subscribe('xhConfigChanged') { ... }` blocks for the common
case.

### Bootstrap Required Resources

Apps declare the configs, prefs, and roles they depend on so a fresh database comes up with the
rows needed. Use the typed spec classes (`ConfigSpec`, `PreferenceSpec`, `RoleSpec`) — they give
you IDE autocomplete, compile-time validation of field names, and a stable contract that mirrors
the seedable fields of the underlying domain class.

Configs and prefs are typically declared from `BootStrap.groovy` by calling the corresponding
service:

```groovy
import io.xh.hoist.config.ConfigSpec
import io.xh.hoist.pref.PreferenceSpec

class BootStrap {

    def configService
    def prefService

    def init = { servletContext ->
        configService.ensureRequiredConfigsCreated([
            new ConfigSpec(
                name: 'myAppPortfolioRefreshInterval',
                valueType: 'int',
                defaultValue: 60,
                groupName: 'PortfolioService',
                note: 'Refresh interval in seconds'
            ),
            new ConfigSpec(
                name: 'myAppPricingSource',
                valueType: 'json',
                defaultValue: [endpoint: 'https://prices.example.com'],
                typedClass: PricingConfig,
                groupName: 'PortfolioService'
            )
        ])

        prefService.ensureRequiredPrefsCreated([
            new PreferenceSpec(
                name: 'myAppPortfolioDefaultView',
                type: 'string',
                defaultValue: 'summary',
                groupName: 'PortfolioService'
            )
        ])
    }
}
```

Roles are declared by overriding `ensureRequiredConfigAndRolesCreated()` in the app's
`RoleService` (extending `DefaultRoleService`):

```groovy
import io.xh.hoist.role.provided.DefaultRoleService
import io.xh.hoist.role.provided.RoleSpec

class RoleService extends DefaultRoleService {

    protected void ensureRequiredConfigAndRolesCreated() {
        super.ensureRequiredConfigAndRolesCreated()

        ensureRequiredRolesCreated([
            new RoleSpec(name: 'APP_USER',  category: 'App', notes: 'Standard access', roles: ['APP_ADMIN']),
            new RoleSpec(name: 'APP_ADMIN', category: 'App', notes: 'Full admin access'),
            new RoleSpec(name: 'TRADER',    category: 'Trading', notes: 'Can execute trades')
        ])
    }
}
```

See [Configuration](configuration.md), [Preferences](preferences.md), and
[Authorization](authorization.md) for the full schemas.

### Use `configService` Typed Getters

Always read config values through `configService.getString`, `getInt`, `getLong`, `getDouble`,
`getBool`, `getMap`, `getList`. Never query `AppConfig.findByName(...)` directly — the typed getters
handle decryption (for `pwd` types), JSON parsing (for `json` types), default values, and
missing-key error messages:

```groovy
// Do
String region = configService.getString('myAppRegion')
int pageSize = configService.getInt('myAppPageSize')
Map opts = configService.getMap('myAppOptions')

// Don't: bypasses type coercion, decryption, and error handling
def region = AppConfig.findByName('myAppRegion').value
```

For JSON configs with a stable, known set of keys, prefer `configService.getObject(Class)` over
`getMap` — it returns a typed `TypedConfigMap` subclass with declared property defaults applied
for any missing keys, and centralizes shape and documentation on the class itself rather than
scattering `?:` fallbacks across call sites:

```groovy
// Do: typed read, defaults baked into the class
PricingConfig config = configService.getObject(PricingConfig)

// Don't: untyped Map plus per-call defaults
def m = configService.getMap('pricingSourceConfig')
def endpoint = m.endpoint ?: 'https://prices.example.com'
```

The class must extend `TypedConfigMap` and be registered with `typedClass:` on its
`ensureRequiredConfigsCreated` entry. See [Configuration](configuration.md#typed-configs-via-typedconfigmap)
for the full guide.

## Controllers and Security

See [Authorization](authorization.md) and [Request Flow](request-flow.md) for the full picture. The
conventions below summarize the standards.

### Mandatory Access Annotations

Every controller endpoint **must** have an access annotation, on either the action method or the
controller class. The framework throws if none is found — there is no implicit default. Method-level
annotations override class-level ones, which is the preferred way to grant a single endpoint broader
access from an otherwise restricted controller:

```groovy
// Do: explicit annotation on every action (or one at class level covering all)
@AccessRequiresRole('TRADER')
class TradeController extends BaseController {

    def list() { renderJSON(tradeService.listForUser()) }

    @AccessAll
    // overrides class-level for one action
    def healthCheck() { renderJSON(status: 'ok') }
}

// Don't: action with no annotation — request will fail
class TradeController extends BaseController {
    def list() { renderJSON(tradeService.listForUser()) }
}
```

Available annotations: `@AccessRequiresRole`, `@AccessRequiresAnyRole`, `@AccessRequiresAllRoles`,
`@AccessAll`. The legacy `@Access` annotation is deprecated. See [Authorization](authorization.md)
for the full annotation reference, role-resolution semantics, and service-layer authorization
patterns.

### `renderJSON` and `parseRequestJSON`

Use `BaseController.renderJSON(...)` for all JSON responses and `parseRequestJSON()` to read JSON
request bodies. These flow through Hoist's [Jackson-based pipeline](json-handling.md) and apply
`JSONFormat` serialization. Grails' built-in `render()` and `request.JSON` use the default Grails
converters and bypass Hoist's serializer:

```groovy
// Do
def list() {
    renderJSON(portfolioService.listAll())
}

def update() {
    def args = parseRequestJSON()
    renderJSON(portfolioService.update(args.id, args.changes))
}

// Don't: bypasses JSONSerializer, JSONFormat, and consistent error rendering
def list() {
    render(contentType: 'application/json') { portfolios = portfolioService.listAll() }
}

def update() {
    def args = request.JSON
    // ...
}
```

### Don't Re-Check Roles in Action Code

`AccessInterceptor` runs **before** the action method, so by the time controller code executes, the
role check has already passed. Repeating it is dead code and a source of drift:

```groovy
// Do: trust the annotation
@AccessRequiresRole('TRADE_MANAGER')
def cancelTrade() {
    tradeService.cancel(params.id)
    renderJSON(status: 'ok')
}

// Don't: redundant check that can desync from the annotation
@AccessRequiresRole('TRADE_MANAGER')
def cancelTrade() {
    if (!user.hasRole('TRADE_MANAGER')) throw new NotAuthorizedException()
    tradeService.cancel(params.id)
    renderJSON(status: 'ok')
}
```

For service-layer authorization (decisions made deeper than a single endpoint), use
`user.hasRole(...)` directly. Annotations are a controller-layer concern.

## GORM and Data Access

See [GORM Domain Objects](gorm-domain-objects.md) for the in-depth guide. Conventions:

### `@ReadOnly` on Query Methods, `@Transactional` on Mutations

Annotate every service method that touches the database. `@ReadOnly` opens a read-only Hibernate
session that skips dirty checking — modestly faster for queries that load many entities, and a
guardrail against accidentally persisting in-method mutations. `@Transactional` opens a writable
transaction and flushes on completion for mutations:

```groovy
import grails.gorm.transactions.ReadOnly
import grails.gorm.transactions.Transactional

class PortfolioService extends BaseService {

    @ReadOnly
    List<Portfolio> listForTrader(String username) {
        Portfolio.findAllByTrader(username)
    }

    @Transactional
    Portfolio update(Long id, Map changes) {
        def portfolio = Portfolio.get(id)
        portfolio.properties = changes
        portfolio.save(flush: true)
        portfolio
    }
}
```

A method without one of these annotations runs without a transaction context, which causes confusing
lazy-loading errors and unexpected flush behavior.

### Avoid N+1 Queries

The most common GORM performance pitfall. Three tools to defeat it:

```groovy
// Do: eager-fetch 1-to-1 / many-to-1 in the domain mapping
class Trade {
    Portfolio portfolio
    static mapping = {
        portfolio fetch: 'join'
    }
}

// Do: batch-load lazy collections
class Portfolio {
    static hasMany = [trades: Trade]
    static mapping = {
        trades batchSize: 50
    }
}

// Do: load many-by-id in a single query
def trades = Trade.findAllByIdInList(tradeIds)

// Don't: per-id lookup loop — generates one query per ID
def trades = tradeIds.collect { Trade.get(it) }
```

### `flush: true` Sparingly

Pass `flush: true` to `save()` only when subsequent code in the same transaction depends on
persisted state being visible (e.g. a follow-up native query, an event subscriber that re-reads).
Never inside a loop — batch the saves and flush once at the end:

```groovy
// Do: single flush at end of batch
@Transactional
void importBatch(List<Map> rows) {
    rows.each {
        new Trade(it).save()
    }
    Trade.withSession { it.flush() }
}

// Don't: flush per row — turns a fast batch into N round-trips
@Transactional
void importBatch(List<Map> rows) {
    rows.each {
        new Trade(it).save(flush: true)
    }
}
```

### Implement `JSONFormat` on Domain and POGO Classes

Domain classes and plain Groovy objects that get serialized to JSON should implement [
`JSONFormat`](json-handling.md) and define `formatForJSON()` to control the rendered shape. Without
it, Jackson falls back to default reflection-based serialization, which often exposes Hibernate
proxies, cyclic associations, or sensitive fields:

```groovy
class Portfolio implements JSONFormat {
    String name
    String trader
    BigDecimal value
    String internalNotes  // not for client

    Object formatForJSON() {
        [
            id    : id,
            name  : name,
            trader: trader,
            value : value
        ]
    }
}
```

## Clustering and Caching

See [Clustering](clustering.md) for full coverage. The conventions below summarize the standards.

### `primaryOnly: true` for Cluster-Wide One-Shot Tasks

Timers and scheduled tasks intended to run once across the entire cluster (scheduled refreshes,
batch jobs, daily rollups) must declare `primaryOnly: true`. Without it, every instance runs the
task — N instances means N executions, which usually causes duplicate work, race conditions, or
repeated emails:

```groovy
// Do: primary-only refresh — runs once cluster-wide
createTimer(
    name: 'refreshTimer',
    runFn: this.&refresh,
    interval: 60 * SECONDS,
    primaryOnly: true
)

// Don't: every instance reads the same external feed every 60s
createTimer(name: 'refreshTimer', runFn: this.&refresh, interval: 60 * SECONDS)
```

For per-instance work (e.g. flushing a local in-memory buffer to a shared cache), omit
`primaryOnly` — every instance should run.

The framing is "instance readiness" not "cluster readiness" — many Hoist apps run as a single
instance, in which case the "primary" is simply the only instance.

### `replicate: true` for Cluster-Shared Caches

`Cache` and `CachedValue` default to **non-replicated** — each instance gets its own copy, which is
fine for instance-local caches but wrong for state that should be globally consistent. For shared
state, set `replicate: true`:

```groovy
// Cluster-shared (replicated)
configCache = createCache(name: 'configByKey', replicate: true)

// Instance-local (default — fine for derived state per node)
sessionCache = createCache(name: 'sessionScratch')
```

For larger datasets that should be partitioned across instances rather than copied to all of them,
use `createIMap`. `IMap` partitions data across the cluster; `Cache` (when `replicate: true`) and
`ReplicatedMap` copy to every node.

### No Non-Serializable Objects in Hazelcast Structures

Anything stored in a distributed `Cache`, `CachedValue`, `IMap`, `ReplicatedMap`, or topic message
must be serializable. GORM domain objects, closures, Spring beans, and other live framework objects
do **not** serialize cleanly:

```groovy
// Do: store a plain Map / POGO
configCache.put(key, [value: cfg.value, lastUpdated: cfg.lastUpdated])

// Don't: store a live GORM domain — Hibernate proxies, lazy collections, sessions
configCache.put(key, AppConfig.findByName(key))
```

Use plain Maps, primitive types, or simple POGOs that have no framework dependencies.

### Eventual Consistency

Hazelcast distributed structures are eventually consistent across the cluster. After a write on one
instance, expect a brief window where another instance still sees the old value. Don't rely on
read-after-write consistency across instances; use idempotent operations and primary-only timers
when ordering matters.

### Repopulate via `forceRun()` in `clearCaches()`

When a service holds a cache that is populated by a `Timer`, calling `clearCaches()` only empties
it — the next request will see an empty cache until the timer's next interval. Call
`refreshTimer.forceRun()` from `clearCaches()` to repopulate immediately:

```groovy
@Override
void clearCaches() {
    super.clearCaches()
    portfolioCache.clear()
    refreshTimer.forceRun()
}
```

## HTTP, Email, and Background Work

### Reuse `JSONClient` Instances

`JSONClient` (see [HTTP Client](http-client.md)) wraps a pooled Apache HttpClient. Instantiate one
per service (or per external endpoint) at `init()` time and reuse it — don't construct a new one per
request:

```groovy
// Do: pooled client, reused across requests
class MarketDataService extends BaseService {
    private JSONClient client

    void init() {
        client = new JSONClient()
    }

    Map fetchQuote(String ticker) {
        client.executeAsMap(new HttpGet("https://quotes.example/$ticker"))
    }
}

// Don't: new client per call — defeats connection pooling
Map fetchQuote(String ticker) {
    new JSONClient().executeAsMap(new HttpGet("https://quotes.example/$ticker"))
}
```

### `async: true` for Email from Request Handlers

`EmailService.sendEmail(...)` is synchronous by default — sending blocks on the SMTP round-trip.
From a request handler or any user-facing path, pass `async: true` so the request doesn't wait on
SMTP:

```groovy
// Do: async send from request path
emailService.sendEmail(
    to: ['ops@example.com'],
    subject: 'Trade error report',
    html: report,
    async: true
)
```

For batch jobs and background timers where total throughput matters more than per-request latency,
synchronous sends are fine.

### `xhEmailOverride` in Dev/Staging

Set the `xhEmailOverride` config in development and staging environments to redirect all outbound
mail to a single inbox. This is a critical safety net — accidentally emailing real users from a
non-production environment is an easy way to cause an incident:

```
xhEmailOverride: dev-team@example.com
```

See [Email](email.md) for details.

### Background Threads and GORM Sessions

Timers and other background threads do not have an automatic Hibernate session. Lazy-loading any
GORM association from a timer throws `LazyInitializationException`. Either eagerly load all needed
data inside a session, or wrap the work in `withNewSession`:

```groovy
// Do: open a session for the background work
createTimer(
    name: 'archiveTimer',
    interval: 1 * HOURS,
    primaryOnly: true,
    runFn: {
        Trade.withNewSession {
            def stale = Trade.findAllByCreatedDateLessThan(thirtyDaysAgo)
            archiveService.archive(stale)
        }
    }
)
```

### Pass `username` from Background Calls to `trackService.track()`

`TrackService.track()` reads the current authenticated user from the request context. Timers and
background threads have no request context, so the call would record an empty user. Pass `username`
explicitly when tracking from background work:

```groovy
// Do: explicit username for background track
trackService.track(
    msg: 'Daily portfolio rollup',
    category: 'PortfolioService',
    username: 'system',
    elapsed: elapsed
)
```

### WebSocket: `pushToAllChannels` vs. `pushToLocalChannels`

[WebSocket](websocket.md) push has two flavors that are easy to mix up:

- **`pushToAllChannels(...)`** — Use from primary-only or single-instance code paths (e.g. a
  `primaryOnly: true` timer or a request handler). Hazelcast relays the push to every connected
  client across the cluster.
- **`pushToLocalChannels(...)`** — Use from code that already runs on every instance (e.g. a
  replicated cache `addEntryListener` callback). The listener fires on every node, so each node
  should push only to its own connected clients.

Picking the wrong one produces either silent missed messages or N-fold duplicates.

## Code Style

### Constructor Pattern

Services do not declare constructors — Grails wires them as Spring beans. Use `init()` for setup,
not `@PostConstruct` or constructor logic. Do declare types on injected dependencies (or use `def`
consistently with the surrounding service):

```groovy
class PortfolioService extends BaseService {

    ConfigService configService
    TrackService trackService

    private Cache<String, Portfolio> portfolioCache

    void init() {
        portfolioCache = createCache(name: 'portfolios', replicate: true)
    }
}
```

### Static Imports for Time Constants

Hoist defines `SECONDS`, `MINUTES`, `HOURS`, `DAYS` (and `MILLISECONDS`) in
`io.xh.hoist.util.DateTimeUtils`. Use them — they read more clearly than raw millisecond literals:

```groovy
import static io.xh.hoist.util.DateTimeUtils.*

createCache(name: 'portfolios', expireTime: 10 * MINUTES)
createTimer(name: 'refresh', interval: 30 * SECONDS, runFn: this.&refresh)
```

### Comments and Section Dividers

Groovy classes don't have a strict member ordering convention, but readability benefits from
grouping. Long services often use comment dividers between logical sections:

```groovy
//------------------
// Implementation
//------------------
private void refresh() { ... }
```

Use these where they help; don't require them everywhere.

### Avoid Unicode in Code Comments

Use plain ASCII in code comments and Groovydoc — em dashes (`—`), curly quotes, and other non-ASCII
characters can break tooling (grep, diff, MCP indexing) and offer no benefit in a monospace context.
Em dashes are fine in narrative markdown documentation where they render naturally.
**Exception:** the `CHANGELOG.md` file follows a stricter plain-ASCII rule - see
[changelog-format.md](./changelog-format.md#general-guidelines).

## Commit Messages, PRs, and Comments

Do **not** hard-wrap lines in commit message bodies, pull request descriptions, or issue/PR
comments. Write each sentence or thought as a single unwrapped line and let the viewing tool (
GitHub, IntelliJ, terminal pager) handle display wrapping:

```
# Do: each thought on a single unwrapped line
Update PortfolioService to use replicated caches so cluster members agree on the canonical state for each portfolio. This removes the per-instance staleness window that surfaced after the last cluster expansion.

Adds `replicate: true` to the two affected caches and removes the now-redundant manual cluster-broadcast that compensated for non-replicated state.
```

```
# Don't: hard-wrapped to ~72 columns — wraps awkwardly in modern viewers
Update PortfolioService to use replicated caches so cluster
members agree on the canonical state for each portfolio. This
removes the per-instance staleness window that surfaced after
the last cluster expansion.
```

The same rule applies to comments on issues and PRs. Hard-wrapping inside a paragraph forces the
viewer to re-wrap your already-wrapped lines, often producing ragged output.

Bullet lists are different — each list item is its own thought and naturally takes its own line.
Don't manually wrap a single bullet across multiple lines.

## Reference

| Convention area                          | Deep reference                                                                                              |
|------------------------------------------|-------------------------------------------------------------------------------------------------------------|
| Service lifecycle and resource factories | [Base Classes](base-classes.md)                                                                             |
| Request flow and exception rendering     | [Request Flow](request-flow.md)                                                                             |
| Authentication and identity              | [Authentication](authentication.md)                                                                         |
| Role-based access control                | [Authorization](authorization.md)                                                                           |
| Soft configuration                       | [Configuration](configuration.md)                                                                           |
| User preferences                         | [Preferences](preferences.md)                                                                               |
| Hazelcast clustering                     | [Clustering](clustering.md)                                                                                 |
| GORM and Hibernate                       | [GORM Domain Objects](gorm-domain-objects.md)                                                               |
| JSON serialization                       | [JSON Handling](json-handling.md)                                                                           |
| Logging and timed blocks                 | [Logging](logging.md)                                                                                       |
| Exception hierarchy                      | [Exception Handling](exception-handling.md)                                                                 |
| HTTP client                              | [HTTP Client](http-client.md)                                                                               |
| Email                                    | [Email](email.md)                                                                                           |
| WebSocket push                           | [WebSocket](websocket.md)                                                                                   |
| CHANGELOG entries                        | [CHANGELOG Entry Format](changelog-format.md)                                                               |
| Sibling client conventions               | [hoist-react Coding Conventions](https://github.com/xh/hoist-react/blob/develop/docs/coding-conventions.md) |
