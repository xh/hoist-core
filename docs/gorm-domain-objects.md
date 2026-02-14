# GORM & Domain Objects

## Overview

GORM (Grails Object Relational Mapping) is the data access layer used across all Hoist applications.
Built on top of Hibernate 5, GORM provides a Groovy-idiomatic API for defining domain classes,
querying databases, managing transactions, and configuring caching. While not a Hoist-specific
concept, GORM knowledge is critical for server-side Hoist development — especially understanding
its caching behavior, association loading strategies, transaction management, and common performance
pitfalls.

This document covers Hibernate/relational database usage as found in Hoist applications. It does
not cover MongoDB or other GORM backends.

Key concepts:
- **Domain classes** live in `grails-app/domain/` and map 1-to-1 to database tables
- **Services** in `grails-app/services/` contain business logic and manage transactions
- **GORM DSL** provides `mapping`, `constraints`, and relationship declarations within domain
  classes
- **Hibernate** handles SQL generation, caching, and session management under the hood

## Source Files (Hoist Core)

These Hoist Core domain classes and services demonstrate the full range of GORM patterns used
across the framework. Application domain classes follow the same conventions.

### Domain Classes

| File | Location | Role |
|------|----------|------|
| `AppConfig` | `grails-app/domain/io/xh/hoist/config/` | Soft configuration — lifecycle hooks, encryption, custom validators |
| `Preference` | `grails-app/domain/io/xh/hoist/pref/` | Preference definitions — one-to-many with cached association |
| `UserPreference` | `grails-app/domain/io/xh/hoist/pref/` | Per-user preference values — `belongsTo`, composite unique |
| `Role` | `grails-app/domain/io/xh/hoist/role/provided/` | Roles — string primary key, eager-fetched `hasMany`, cascade delete |
| `RoleMember` | `grails-app/domain/io/xh/hoist/role/provided/` | Role memberships — enum field, composite unique, `belongsTo` |
| `TrackLog` | `grails-app/domain/io/xh/hoist/track/` | Activity tracking — multiple indices, custom cache eviction, `autoTimestamp false` |
| `Monitor` | `grails-app/domain/io/xh/hoist/monitor/` | Health monitors — simple domain with `inList` validators |
| `JsonBlob` | `grails-app/domain/io/xh/hoist/jsonblob/` | JSON storage — criteria queries, custom unique validator, soft delete |
| `LogLevel` | `grails-app/domain/io/xh/hoist/log/` | Log level overrides — column remapping, multiple lifecycle hooks |

### Services

| File | Location | Role |
|------|----------|------|
| `ConfigService` | `grails-app/services/io/xh/hoist/config/` | `@ReadOnly`/`@Transactional`, dynamic finders with `[cache: true]` |
| `PrefService` | `grails-app/services/io/xh/hoist/pref/` | Typed getters/setters, `findByPreferenceAndUsername` |
| `DefaultRoleUpdateService` | `grails-app/services/io/xh/hoist/role/provided/` | CRUD with `flush: true`, `addToMembers`/`removeFromMembers` |
| `TrackService` | `grails-app/services/io/xh/hoist/track/` | Async persistence via `task { TrackLog.withTransaction {} }` |
| `JsonBlobService` | `grails-app/services/io/xh/hoist/jsonblob/` | Criteria queries with projections, `DataBinder` integration |
| `LogLevelService` | `grails-app/services/io/xh/hoist/log/` | `findAllByLevelIsNotNull()`, timer-based recalculation |

## Domain Class Anatomy

A GORM domain class is a Groovy class in `grails-app/domain/` that maps to a database table.
The class body contains field declarations, a `mapping` block, a `constraints` block, optional
association declarations, lifecycle hooks, and typically a `formatForJSON()` method.

### Fields and Types

Fields are declared as standard Groovy properties. GORM maps them to database columns automatically.

```groovy
class AppConfig implements JSONFormat {
    String name
    String value
    String valueType = 'string'       // Default value
    boolean clientVisible = false     // Primitive boolean with default
    String lastUpdatedBy
    Date lastUpdated
    String groupName = 'Default'
}
```

Enum types are supported directly:

```groovy
class RoleMember implements JSONFormat {
    enum Type { USER, DIRECTORY_GROUP, ROLE }

    Type type          // Stored as a string in the database
    String name
}
```

### The `mapping` Block

The `static mapping` closure configures how the domain maps to the database:

```groovy
static mapping = {
    table 'xh_config'             // Explicit table name (Hoist Core uses xh_ prefix)
    cache true                     // Enable second-level (Hibernate) cache

    value type: 'text'             // Map to TEXT column instead of VARCHAR
}
```

Common mapping options:

| Option | Example | Purpose |
|--------|---------|---------|
| `table` | `table 'xh_config'` | Explicit table name |
| `table` with schema | `table name: 'my_table', schema: 'dbo'` | Table in a non-default schema |
| `cache true` | | Enable Hibernate second-level cache |
| `type: 'text'` | `value type: 'text'` | Use TEXT column for large strings |
| `column:` | `level column: 'log_level'` | Custom column name (useful for reserved words) |
| `index:` | `username index: 'idx_track_username'` | Create a database index |
| `autoTimestamp false` | | Disable automatic `dateCreated`/`lastUpdated` management |
| `id` | `id name: 'name', generator: 'assigned', type: 'string'` | Custom primary key strategy |

**Custom ID strategies.** By default, GORM uses auto-incrementing `Long` IDs. The `Role` domain
uses an assigned string primary key — the role name serves as the ID:

```groovy
static mapping = {
    table 'xh_role'
    id name: 'name', generator: 'assigned', type: 'string'
    cache true
}
```

**Schema separation.** Applications can configure a default schema for Hoist Core's `xh_` tables
while placing business domain tables in a different schema:

```groovy
// In application.groovy
hibernate {
    default_schema = 'app'        // Default schema for app tables
}

// In a business domain class
static mapping = {
    table name: 'my_table', schema: 'dbo'   // Override to business schema
}
```

### The `constraints` Block

The `static constraints` closure defines validation rules:

```groovy
static constraints = {
    name(unique: true, nullable: false, blank: false, maxSize: 50)
    value(nullable: false, blank: false, validator: AppConfig.isValid)
    valueType(inList: AppConfig.TYPES)
    note(nullable: true, maxSize: 1200)
    lastUpdatedBy(nullable: true, maxSize: 50)
}
```

Common constraint options:

| Constraint | Example | Purpose |
|-----------|---------|---------|
| `nullable` | `nullable: true` | Allow null values (default is `false`) |
| `blank` | `blank: false` | Disallow empty strings |
| `maxSize` | `maxSize: 50` | Maximum string length |
| `unique` | `unique: true` | Simple uniqueness |
| `unique` (composite) | `unique: 'preference'` | Unique within a parent (`username` unique per `preference`) |
| `unique` (multi-field) | `unique: ['type', 'role']` | Composite unique across multiple fields |
| `inList` | `inList: ['Floor', 'Ceil', 'None']` | Value must be in a fixed list |
| `validator` | `validator: { val, obj -> ... }` | Custom validation closure |

**Custom validators** receive the value and the object being validated. Return `true` for valid
or a message key string for invalid:

```groovy
static isValid = { String val, AppConfig obj ->
    if (obj.valueType == 'int' && !val.isInteger())
        return 'default.invalid.integer.message'
    return true
}
```

### Associations

GORM supports standard Hibernate association types:

```groovy
// Parent side — one-to-many
class Preference {
    static hasMany = [userPreferences: UserPreference]
}

// Child side — many-to-one
class UserPreference {
    static belongsTo = [preference: Preference]
}
```

**`hasMany`** declares a collection of child objects. **`belongsTo`** establishes ownership — the
child's lifecycle is tied to its parent's.

**`mappedBy: 'none'`** disables unwanted bidirectional association inference. When a domain class
has a field referencing another domain, GORM may attempt to create a back-reference. Use
`mappedBy` to prevent this:

```groovy
class Company {
    // An internal user assigned as account manager for this company
    AppUser accountManager

    // Prevent GORM from creating an automatic Company back-reference on AppUser
    static mappedBy = [accountManager: 'none']
}
```

### Lifecycle Hooks

Domain classes can define callbacks that fire during persistence operations:

| Hook | When it Fires |
|------|---------------|
| `beforeInsert` | Before a new record is inserted |
| `beforeUpdate` | Before an existing record is updated |
| `afterInsert` | After a new record is inserted |
| `afterUpdate` | After an existing record is updated |
| `afterDelete` | After a record is deleted |

**Data normalization** — `RoleMember` normalizes usernames to lowercase on insert and update:

```groovy
def beforeInsert() {
    if (type == Type.USER) name = name?.toLowerCase()
}

def beforeUpdate() {
    if (type == Type.USER) name = name?.toLowerCase()
}
```

**Encryption** — `AppConfig` encrypts password values before persistence:

```groovy
def beforeInsert() { encryptIfPwd(true) }
def beforeUpdate() {
    encryptIfPwd(false)
    // Fire change event asynchronously with delay
    if (hasChanged('value')) {
        task {
            Thread.sleep(500)
            Utils.configService.fireConfigChanged(this)
        }
    }
}
```

**Async event firing** — Both `AppConfig` and `LogLevel` use `grails.async.Promises.task {}` with
a `Thread.sleep(500)` delay in lifecycle hooks to fire events after the transaction has committed
and the change has propagated. This is a deliberate pattern — firing synchronously within the hook
could emit events before the data is actually visible to other sessions.

### `JSONFormat` Implementation

All Hoist Core domain classes implement the `JSONFormat` interface and provide a `formatForJSON()`
method. This is the standard convention for producing JSON representations consumed by the Hoist
React client:

```groovy
class Monitor implements JSONFormat {
    // ... fields ...

    Map formatForJSON() {
        return [
            id       : id,
            code     : code,
            name     : name,
            active   : active,
            // ... all fields needed by the client
        ]
    }
}
```

Some domains provide multiple serialization methods for different contexts — for example,
`JsonBlob` has `formatForJSON()` for admin views and `formatForClient()` for end-user views with
parsed JSON values.

## Querying

GORM provides several query mechanisms. Choose based on complexity and type-safety needs.

### Dynamic Finders

The most common query pattern in Hoist code. GORM generates finder methods from field names:

```groovy
// Single record
def config = AppConfig.findByName('myConfig')

// Multiple records
def visible = AppConfig.findAllByClientVisible(true)

// Multiple fields
def blob = JsonBlob.findByTypeAndNameAndOwnerAndArchivedDate(type, name, owner, 0)

// Greater-than, case-insensitive, etc.
def errors = TrackLog.findAllByDateCreatedGreaterThanEqualsAndCategory(since, 'Client Error')
def existing = Role.findByNameIlike(name)    // Case-insensitive like

// With cache enabled (query cache)
def config = AppConfig.findByName(name, [cache: true])
def prefs = UserPreference.findAllByUsername(username, [cache: true])
```

**Avoid:** Looping with individual finders when you have a list — use `findAllByFieldInList` instead:

```groovy
// ✅ Do: Use findAllByFieldInList
def results = UserPreference.findAllByPreferenceInList(jsonPrefs)

// ❌ Don't: Loop with individual finders (N+1 pattern)
jsonPrefs.each { pref -> UserPreference.findByPreference(pref) }
```

### `list()` and `get()`

```groovy
// Load all records
def allConfigs = AppConfig.list()

// Load by ID
def role = Role.get('HOIST_ADMIN')

// Override fetch strategy at query time (avoid N+1 on associations)
def portfolios = Portfolio.list(fetch: [positions: 'eager'])
```

### Where Queries

Type-safe closure syntax for more complex conditions:

```groovy
// Used in JsonBlob's custom unique validator
static boolean isNameUnique(String blobName, JsonBlob blob) {
    !where {
        name == blobName &&
            type == blob.type &&
            archivedDate == blob.archivedDate &&
            owner == blob.owner &&
            token != blob.token
    }
}
```

### Criteria Queries

For complex queries with OR conditions, projections, and dynamic query building:

```groovy
// From JsonBlobService — OR conditions with optional projection
private Object accessibleBlobs(String type, String username, String projection = null) {
    JsonBlob.createCriteria().list {
        eq('type', type)
        eq('archivedDate', 0L)
        or {
            eq('owner', username)
            like('acl', '*')
        }

        if (projection) {
            projections { property(projection) }
        }
    }
}
```

### Direct SQL via `groovy.sql.Sql`

For data that lives outside GORM's domain model — external tables, bulk operations, stored
procedures, or complex joins that don't map well to GORM:

```groovy
// Service pattern: inject DataSource directly
class DatabaseService {
    DataSource dataSource

    List<Map> rows(String query) {
        // try-with-resources: Sql implements Closeable, so the connection is
        // automatically closed when the block exits (even on exception).
        try (def sql = new Sql(dataSource)) {
            return sql.rows(query)
        }
    }

    void withTransaction(Closure closure) {
        try (def sql = new Sql(dataSource)) {
            sql.withTransaction { closure(sql) }
        }
    }
}
```

This pattern bypasses GORM entirely — no domain class mapping, no Hibernate caching, no
automatic dirty checking. Use it when you need direct database access for tables not managed by
GORM. **Always use try-with-resources** (`try (def sql = ...)`) to ensure the `Sql` connection
is closed — leaking connections will exhaust the pool under load.

**Note:** Hoist Core does not use HQL — all queries use GORM's DSL or direct SQL.

## Transaction Management

### `@ReadOnly`

Use on all service methods that only read data. This creates a read-only Hibernate session that
skips dirty checking, providing a meaningful performance benefit:

```groovy
@ReadOnly
Map getClientConfig() {
    def ret = [:]
    AppConfig.findAllByClientVisible(true, [cache: true]).each {
        ret[it.name] = it.externalValue(obscurePassword: true, jsonAsObject: true)
    }
    return ret
}
```

### `@Transactional`

Use on all service methods that create, update, or delete domain objects:

```groovy
@Transactional
AppConfig setValue(String name, Object value, String lastUpdatedBy = authUsername) {
    def currConfig = AppConfig.findByName(name, [cache: true])
    currConfig.value = value as String
    currConfig.lastUpdatedBy = lastUpdatedBy
    currConfig.save(flush: true)
}
```

### `flush: true`

By default, Hibernate batches SQL operations and may not execute them immediately. Use
`flush: true` on `save()` and `delete()` when you need the SQL to execute immediately — before
reading the data back, before the session closes, or when the data must be visible to subsequent
operations in the same request:

```groovy
// ✅ Do: Flush when subsequent code depends on the persisted data
role.save(flush: true)

// ✅ Do: Flush on deletes
roleToDelete.delete(flush: true)
```

### `withTransaction {}`

Use explicit transaction blocks for code running outside a service method context — particularly
in async tasks or callbacks:

```groovy
// From TrackService — async persistence on a background thread
doPersist ?
    task { TrackLog.withTransaction(processFn) } :
    task(processFn)
```

### `withNewTransaction {}`

Creates an independent transaction that commits or rolls back independently of the surrounding
transaction. Use when you need to persist partial results even if the overall operation fails:

```groovy
// Process each item in its own transaction — failures don't roll back others
itemIds.each { Long itemId ->
    try {
        MyDomain.withNewTransaction {
            def item = MyDomain.get(itemId)
            processItem(item)
        }
    } catch (Exception e) {
        logError("Failed to process item $itemId", e)
        // Other items continue processing
    }
}
```

### `withNewSession {}`

Creates a fresh Hibernate session, critical for cache priming during `init()`. During service
initialization, you want to load data into the Hibernate cache without polluting the session that
handles subsequent requests:

```groovy
void init() {
    Portfolio.withNewSession {
        // Eagerly load all positions to avoid N+1 queries during later access
        withInfo(['Priming Hibernate cache', 'Portfolio']) {
            Portfolio.list(fetch: [positions: 'eager'])
        }
    }
}
```

## Associations and Fetching Strategies

### Lazy Loading (Default)

By default, associations are proxied and loaded only on first access. This avoids loading the
entire object graph but can cause performance issues when associations are accessed in loops
(the N+1 problem — see next section).

### Eager Loading

Force an association to always load with its parent:

```groovy
static mapping = {
    // Option 1: lazy: false — separate query but always loaded
    company lazy: false

    // Option 2: fetch: 'join' — loaded in the same SQL query via JOIN
    currentVersion fetch: 'join'
}
```

**`fetch: 'join'`** is best for 1-to-1 or many-to-1 relationships where the associated object is
almost always needed. Consider a `Trade` domain with a `currentPosition` — eagerly fetching the
1-to-1 relationship avoids a second query on every list:

```groovy
/**
 * Eagerly loaded via fetch:join — an efficient query as Trade and
 * current Position have a 1-1 relationship. Required for bulk
 * listing, referenced off of formatForJSON.
 */
Position currentPosition

/** Lazily loaded (default) — usually is the same as currentPosition and is
 *  a cache hit. Not required for bulk listing. */
Position lastConfirmedPosition

static mapping = {
    currentPosition fetch: 'join'
    // positions and lastConfirmedPosition remain lazy (default)
}
```

### Batch Size

Controls how many lazy associations are loaded in a single query when any one of them is accessed.
Instead of loading one-at-a-time (N+1), Hibernate loads them in batches:

```groovy
static mapping = {
    positions batchSize: 1000, cache: true, cascade: 'all-delete-orphan'
}
```

### Cascade

Controls which operations propagate from parent to children:

| Cascade | Effect |
|---------|--------|
| `'all-delete-orphan'` | All operations cascade; removing a child from the collection deletes it |
| `'all'` | All operations cascade; orphans are not auto-deleted |

```groovy
// Role — deleting a Role also deletes all its RoleMembers
static mapping = {
    members cascade: 'all-delete-orphan', fetch: 'join', cache: true
}
```

### Overriding Fetch Strategy at Query Time

You can override the mapping-level fetch strategy on individual queries:

```groovy
// Load all Portfolios with their positions eagerly (even though positions is lazy in mapping)
Portfolio.list(fetch: [positions: 'eager'])

// Override to lazy on a normally-eager association
Trade.findAll([fetch: [currentPosition: 'lazy']])
```

## The N+1 Query Problem

### What It Is

When you load a list of N parent objects and then access a lazy association on each one, Hibernate
executes 1 query for the parents + N additional queries for each association access:

```groovy
// ❌ N+1 problem: 1 query loads all Portfolios, then N queries load each portfolio's positions
def portfolios = Portfolio.list()     // 1 query
portfolios.each { portfolio ->
    portfolio.positions.each { ... }    // +1 query per portfolio = N queries
}
```

### How to Detect

Enable SQL logging during development (see [SQL Logging](#sql-logging-and-performance-validation))
and watch for:
- Unexpected query counts (dozens or hundreds of queries for a single request)
- Repeated identical query patterns with different bind parameters
- Missing JOINs where you expect them

### Mitigation Strategies

1. **`fetch: 'join'` in mapping** — For 1-to-1 associations always needed with the parent

2. **`lazy: false` in mapping** — For small collections always accessed together

3. **`batchSize`** — Load lazy associations in batches instead of one-at-a-time:
   ```groovy
   positions batchSize: 1000, cache: true
   ```

4. **Query-time fetch overrides** — Eagerly load for specific queries without changing the default:
   ```groovy
   Portfolio.list(fetch: [positions: 'eager'])
   ```

5. **In-memory stub caches** — For high-traffic read paths, build a denormalized in-memory cache
   that bypasses GORM entirely. Pre-compute and cache lightweight stub objects that contain all the
   data needed for common operations without touching the database at all.

6. **Second-level cache** — Once an association is loaded, the L2 cache can serve subsequent
   accesses without additional queries (see next section).

## Second-Level Cache (Hibernate + Hazelcast)

### What It Is

Hibernate's second-level (L2) cache stores domain objects and query results across sessions. In
Hoist, the L2 cache is backed by Hazelcast, which means cached data is shared across all cluster
instances.

This is distinct from the first-level cache (Hibernate session cache), which stores objects only
within a single session/transaction.

### Enabling

Add `cache true` in the domain's mapping block — used on virtually all Hoist domains:

```groovy
static mapping = {
    cache true
}
```

### Association Caching

Cache an association's collection separately:

```groovy
static mapping = {
    userPreferences cache: true     // Cache Preference's UserPreference collection
    members cascade: 'all-delete-orphan', fetch: 'join', cache: true
}
```

### Query Cache

Enable per-query caching by passing `[cache: true]` as a finder parameter:

```groovy
// Results are cached — subsequent calls with same params skip the database
AppConfig.findByName(name, [cache: true])
AppConfig.findAllByClientVisible(true, [cache: true])
UserPreference.findAllByUsername(username, [cache: true])
```

### Custom Cache Configuration

Override Hazelcast cache eviction and expiry policies on a per-domain basis:

```groovy
// TrackLog — limit cache to 20,000 entries (high-volume table)
static cache = {
    evictionConfig.size = 20000
}
```

### Admin Console Tools

The Hoist Admin Console provides tools for inspecting and managing Hibernate caches at runtime:
- **Cache statistics** — view hit/miss rates, entry counts, and memory usage per cache region
- **Cache clearing** — clear individual domain caches or all caches at once, useful when
  troubleshooting stale data issues or after direct database modifications

These tools are available under the Admin Console's cluster/cache management views.

### When to Disable

For large, user-specific result sets where caching would consume excessive memory or return stale
data, disable caching on the query:

```groovy
// Large result set — caching would be counterproductive
def results = SomeDomain.createCriteria().list {
    // ... complex query ...
    cache false
}
```

## Circular Dependencies and Save Ordering

### The Problem

Bidirectional associations with non-nullable foreign keys create a chicken-and-egg problem: you
can't insert the parent without the child FK, but you can't insert the child without the parent FK.

### The Pattern

Consider a `Trade` with a `currentPosition` field (FK to `Position`), where each `Position`
belongs to a `Trade`. To break the cycle:

1. Make the "second" FK nullable in constraints (with a comment explaining why)
2. Save the parent first without the circular reference
3. Create and save the child
4. Update the parent with the child reference and save again

```groovy
static constraints = {
    /**
     * currentPosition is not expected to be null, except very briefly when this
     * trade is initially created. This field must be nullable in the DB,
     * otherwise we have a circular dependency between trade.current_position_id
     * and position.trade_id.
     */
    currentPosition nullable: true
}
```

The comment is critical documentation — without it, a future developer might remove the `nullable`
constraint and introduce a constraint violation during creation.

## SQL Logging and Performance Validation

### Hibernate SQL Logging

Configure in `application.groovy`:

```groovy
hibernate {
    show_sql = false               // Set to true to log all generated SQL to stdout
    use_sql_comments = true        // Adds GORM/HQL context as SQL comments for easier debugging
}
```

For more granular control via Logback:

| Logger | Level | Output |
|--------|-------|--------|
| `org.hibernate.SQL` | `DEBUG` | Generated SQL statements |
| `org.hibernate.type.descriptor.sql` | `TRACE` | SQL bind parameter values |

### Dynamic Configuration via Hoist Admin

Hoist's `LogLevel` admin UI allows enabling Hibernate SQL logging at runtime without a restart.
Navigate to the Admin Console > Logging tab and set the appropriate logger level. This is the
recommended approach for investigating query behavior in a running application.

### What to Look For

When reviewing SQL output, watch for:
- **Unexpected query counts** — a single endpoint generating dozens of queries
- **Repeated identical queries** with different bind parameters (N+1 symptom)
- **Missing JOINs** — separate queries for data that should be fetched together
- **Excessive lazy loads** — associations being loaded one-at-a-time in a loop

### Recommendation

Enable SQL logging during development and code review for any GORM-heavy feature work. Compare
the expected vs. actual query count — if a list endpoint generates more than a handful of queries,
investigate whether eager loading, batch size, or caching improvements are needed.

## Common Patterns

### Table Naming

Hoist Core uses the `xh_` prefix for all framework tables: `xh_config`, `xh_role`,
`xh_track_log`, etc. This prefix is intended for Hoist tables only.

### Async Persistence

For operations that run on background threads (outside a Grails service method), wrap persistence
in an explicit transaction:

```groovy
// TrackService — persistence on a background thread
task { TrackLog.withTransaction(processFn) }
```

### Lifecycle Hooks with Delayed Events

When domain lifecycle hooks need to fire events, use a new thread with a delay to ensure the
transaction has committed:

```groovy
def beforeUpdate() {
    if (hasChanged('value')) {
        task {
            Thread.sleep(500)
            Utils.configService.fireConfigChanged(this)
        }
    }
}
```

### `formatForJSON()`

Implement the `JSONFormat` interface on all domain classes. The `formatForJSON()` method returns a
`Map` that is serialized to JSON by Hoist's `JSONSerializer`:

```groovy
class Monitor implements JSONFormat {
    Map formatForJSON() {
        return [
            id    : id,
            code  : code,
            name  : name,
            active: active
            // Include only fields the client needs
        ]
    }
}
```

### `addToMembers` / `removeFromMembers`

GORM's collection management methods handle bidirectional relationship bookkeeping automatically:

```groovy
// Add a member to a role
role.addToMembers(type: USER, name: 'jsmith', createdBy: authUsername)
role.save(flush: true)

// Remove a member
role.removeFromMembers(memberToRemove)
role.save(flush: true)
```

## Common Pitfalls

### Forgetting `flush: true` on saves

Without explicit flushing, Hibernate may defer SQL execution. Data may not be visible to
subsequent reads in the same session, and changes may be lost if the session closes without
a flush:

```groovy
// ✅ Do: Flush when the data must be immediately visible
config.save(flush: true)

// ❌ Don't: Save without flush when subsequent code reads the data back
config.save()
def reloaded = AppConfig.findByName(config.name)  // May return stale data
```

Conversely, avoid flushing inside a loop. Each `flush: true` triggers a full Hibernate dirty check
and SQL round-trip — in a loop this adds up to significant overhead:

```groovy
// ❌ Don't: Flush on every iteration
items.each { item ->
    item.status = 'PROCESSED'
    item.save(flush: true)    // N dirty checks + N round-trips
}

// ✅ Do: Save without flush in the loop, flush once at the end
items.each { item ->
    item.status = 'PROCESSED'
    item.save()
}
items.first()?.save(flush: true)  // Single flush triggers all pending SQL
```

### Accessing lazy associations outside a session

A `LazyInitializationException` occurs when you access an unloaded association after the Hibernate
session has closed — common in async code or after returning from a `@ReadOnly` method:

```groovy
// ❌ This will fail if 'members' wasn't loaded within the session
@ReadOnly
Role getRole(String name) {
    return Role.get(name)     // Session closes after method returns
}

// Later, outside any session:
role.members.each { ... }    // LazyInitializationException!
```

**Fix:** Eagerly load the association in the mapping (`fetch: 'join'` or `lazy: false`), or access
the association within the transactional method before returning.

### N+1 queries in loops

Iterating a collection and accessing a lazy association on each item generates N additional
queries:

```groovy
// ❌ N+1: Each iteration triggers a separate SQL query
def portfolios = Portfolio.list()
portfolios.each { p ->
    println p.positions.size()    // 1 query per portfolio!
}

// ✅ Fix: Load with eager fetch override
def portfolios = Portfolio.list(fetch: [positions: 'eager'])
```

### Circular FK dependencies

Bidirectional associations with non-nullable foreign keys require careful save ordering. See
[Circular Dependencies and Save Ordering](#circular-dependencies-and-save-ordering) — always
make the "second" FK nullable with a comment explaining why.

### Missing `@Transactional` / `@ReadOnly`

Methods without transaction annotations get GORM's default behavior, which may not be what you
expect. Always annotate service methods explicitly:

```groovy
// ✅ Do: Always annotate
@ReadOnly
List<AppConfig> listConfigs() { ... }

@Transactional
void updateConfig(String name, String value) { ... }

// ❌ Don't: Rely on implicit transaction behavior
List<AppConfig> listConfigs() { ... }
```

### Modifying domain objects in `@ReadOnly` methods

Changes made to domain objects within a `@ReadOnly` method may or may not be flushed depending on
context. The behavior is unpredictable — sometimes changes persist, sometimes they don't. Always
use `@Transactional` for any method that modifies domain objects:

```groovy
// ❌ Don't: Modify in a read-only method
@ReadOnly
void doSomething() {
    def config = AppConfig.findByName('foo')
    config.value = 'bar'     // May or may not persist — undefined behavior
}

// ✅ Do: Use @Transactional for writes
@Transactional
void doSomething() {
    def config = AppConfig.findByName('foo')
    config.value = 'bar'
    config.save(flush: true)
}
```

### Leaking `Sql` connections

When using `groovy.sql.Sql` for direct database access, failing to close the `Sql` instance leaks
connections back to the pool. Under load, this exhausts the pool and causes the application to hang.
Always use try-with-resources:

```groovy
// ✅ Do: try-with-resources auto-closes the connection
try (def sql = new Sql(dataSource)) {
    return sql.rows(query)
}

// ❌ Don't: connection is never returned to the pool
def sql = new Sql(dataSource)
def results = sql.rows(query)
```
