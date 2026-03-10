# CLAUDE.md

This file provides guidance to AI coding assistants — including Claude Code, GitHub Copilot, and
similar tools — when working with code in this repository.

## Project Overview

Hoist-core is the server-side component of the Hoist web application development toolkit, built
by Extremely Heavy Industries (xh.io). It is a **Grails 7 plugin** (not a standalone app)
published as `io.xh:hoist-core` and consumed by Grails application projects. The client-side
counterpart is [hoist-react](https://github.com/xh/hoist-react).

- **Language**: Groovy 4 / Java 17
- **Framework**: Grails 7.0 (Spring Boot 3.5, Hibernate 5, GORM)
- **Clustering**: Hazelcast 5.6 for distributed caching, pub/sub, and multi-instance coordination
- **Package root**: `io.xh.hoist`

## Hoist Developer Tools and Documentation

**IMPORTANT: Do not guess at hoist-core APIs, service patterns, or framework conventions.**
Hoist-core ships a dedicated MCP server that provides structured access to all framework
documentation and Groovy/Java symbol information. **You MUST use these tools before modifying or
extending hoist-core code** to understand existing architecture, configuration patterns, and
common pitfalls. The feature docs and concept docs are the authoritative reference for how Hoist
Core works -- skipping them risks producing code that conflicts with established patterns or
misses built-in functionality.

The MCP server is configured via `.mcp.json` and is very likely already available. Use the
following tools:

- `hoist-core-search-docs` -- Search all docs by keyword (e.g. `"BaseService lifecycle"`,
  `"authentication OAuth"`, `"clustering Hazelcast"`)
- `hoist-core-list-docs` -- List all available documentation, grouped by category
- `hoist-core-search-symbols` -- Search for Groovy/Java classes, interfaces, traits, enums, and
  members of key framework classes (e.g. `"Cache"`, `"createTimer"`)
- `hoist-core-get-symbol` -- Get detailed type info for a specific symbol (signature, Groovydoc,
  inheritance, annotations)
- `hoist-core-get-members` -- List all properties and methods of a class or interface

**Recommended workflow:** Start with `hoist-core-list-docs` or `hoist-core-search-docs` to
discover relevant documentation. Read the applicable doc(s) to understand architectural context
and common pitfalls. Supplement with symbol lookups (`search-symbols`, `get-symbol`,
`get-members`) for precise API details -- exact signatures, annotations, and member listings.

The documentation index is also available directly at [`docs/README.md`](docs/README.md), with
a "Quick Reference by Task" table mapping common goals to the right doc. See
[`docs/planning/docs-roadmap.md`](docs/planning/docs-roadmap.md) for documentation coverage
tracking and writing conventions.

### GitHub MCP Server (opt-in)

A Docker-based server providing GitHub API tools (issues, PRs, code search, etc.) via the
official `github-mcp-server` image. This server is configured in `.mcp.json` but **not enabled
by default** — it requires Docker and a GitHub token, which not all developers will have
running. If you work with GitHub issues, PRs, or code search, enabling it is recommended. To
do so:

1. Install and start **Docker**
2. Set the **`GITHUB_TOKEN`** environment variable to a GitHub Personal Access Token
3. Add `"github"` to `enabledMcpjsonServers` in `.claude/settings.local.json`:
   ```json
   {
     "enabledMcpjsonServers": ["hoist-core", "github"]
   }
   ```

Local settings merge with the shared `settings.json`, so enabling it locally does not affect
other developers. If Docker is not running or the token is not set when the server is enabled,
Claude Code may show errors on startup — remove `"github"` from your local settings to
resolve.

### JetBrains IntelliJ MCP Server (opt-in)

A JetBrains MCP server is also configured in `.mcp.json`, providing tools for interacting with
the IntelliJ IDE (file navigation, code inspections, refactoring, terminal commands, etc.).
This server must be enabled within IntelliJ's settings and requires a running IDE instance to
connect. Add `"jetbrains"` to `enabledMcpjsonServers` in `.claude/settings.local.json` to
enable it for Claude Code.

## Build Commands

```bash
./gradlew assemble               # Compile all sources (Groovy + Java) and build the JAR
./gradlew clean assemble         # Clean rebuild
```

This is a plugin — `bootRun` is not supported. To run locally, use a wrapper app project that
includes hoist-core as a dependency.

## Source Layout

```
grails-app/
  controllers/io/xh/hoist/    # BaseController, RestController, UrlMappings, admin/impl endpoints
  domain/io/xh/hoist/          # GORM domain classes (AppConfig, TrackLog, Monitor, Preference, etc.)
  services/io/xh/hoist/        # Grails services (ConfigService, ClusterService, TrackService, etc.)
  init/io/xh/hoist/            # BootStrap, Application, ClusterConfig, LogbackConfig

src/main/groovy/io/xh/hoist/   # Core library code (non-Grails-artifact classes)
  BaseService.groovy            # Abstract base for ALL services — provides caches, timers, identity
  HoistFilter.groovy            # Servlet filter: auth gating, cluster readiness, exception catching
  HoistCoreGrailsPlugin.groovy  # Plugin descriptor — Hazelcast init, filter registration, shutdown
  admin/                        # Admin console support
  cache/ + cachedvalue/         # Distributed caching (Hazelcast-backed Cache<K,V>, CachedValue<T>)
  cluster/                      # ClusterService, multi-instance coordination, distributed execution
  configuration/                # ApplicationConfig support
  data/filter/                  # Data filtering utilities
  exception/                    # Exception hierarchy (HttpException, RoutineException, etc.)
  http/                         # HTTP client & proxy services
  json/ + json/serializer/      # Jackson-based JSON serialization/parsing (JSONSerializer, JSONParser)
  role/                         # BaseRoleService, DefaultRoleService, access annotations
  security/                     # BaseAuthenticationService, access annotations (@AccessRequiresRole, etc.)
  user/                         # HoistUser trait, BaseUserService
  util/                         # Utils, Timer, DateTimeUtils, InstanceConfigUtils
  websocket/                    # WebSocket support (cluster-aware push)
```

## Architecture

### Request Flow

HTTP requests pass through `HoistFilter` (servlet filter) which ensures the cluster is running
and delegates to `BaseAuthenticationService.allowRequest()`. Requests then route via
`UrlMappings` to controllers extending `BaseController` (general endpoints) or
`RestController` (CRUD operations).

### Service Layer

All framework and application services extend `BaseService`, which provides:

- **Lifecycle**: `init()` for startup, `destroy()` for shutdown, `parallelInit()` for batch
  startup
- **Distributed resources** (Hazelcast-backed): `createCache()`, `createCachedValue()`,
  `createTimer()`, `createIMap()`, `createReplicatedMap()`
- **Event systems**: `subscribe()` (local Grails events), `subscribeToTopic()` (cluster-wide
  pub/sub)
- **Identity access**: `getUser()`, `getUsername()`, `getAuthUser()`, `getAuthUsername()`
- **Admin stats**: `getAdminStats()` for the Admin Console

Services are Spring-managed singletons, accessed via DI or static `Utils` accessors.

### Controller Layer

- `BaseController`: JSON rendering (`renderJSON`), request parsing (`parseRequestJSON`), async
  support, OWASP encoding, cluster result rendering
- `RestController`: Template-method CRUD (`doCreate`, `doList`, `doUpdate`, `doDelete`) with
  `restTarget` pointing to a domain class
- URL pattern: `/rest/$controller/$id?` for REST, `/$controller/$action?/$id?` for general

### Authentication & Authorization

Apps must implement three abstract services:

- `AuthenticationService` (extends `BaseAuthenticationService`) — defines auth scheme
- `UserService` (extends `BaseUserService`) — user lookup, HoistUser creation
- `RoleService` (extends `BaseRoleService`) — role assignment (or use `DefaultRoleService`)

Controller access is secured via annotations: `@AccessRequiresRole`, `@AccessRequiresAnyRole`,
`@AccessRequiresAllRoles`, `@AccessAll`. Every controller endpoint must have one or an exception
is thrown.

Built-in roles: `HOIST_ADMIN`, `HOIST_ADMIN_READER`, `HOIST_IMPERSONATOR`,
`HOIST_ROLE_MANAGER`.

### Clustering (Hazelcast)

`ClusterService` manages multi-instance coordination. The primary instance (oldest member)
handles primary-only tasks (e.g., timers with `primaryOnly: true`). Distributed data structures
(IMap, ReplicatedMap, Topic) are named using the pattern `{ClassName}[{resourceName}]`.

### Soft Configuration

`AppConfig` domain objects store typed config values (`string|int|long|double|bool|json|pwd`)
in the database. `ConfigService` provides typed getters. Configs can be marked `clientVisible`
for the JS client. The `pwd` type stores values encrypted via Jasypt.

### JSON Handling

Custom Jackson-based `JSONSerializer` and `JSONParser` — not Grails' default JSON converters.
Controllers use `renderJSON()` and `parseRequestJSON()`. Custom serializers are registered via
`JSONSerializer.registerModules()`.

## Key Conventions

### Naming

- **Event names**: Prefixed with `xh` (e.g., `xhConfigChanged`, `xhTrackReceived`)
- **Config names**: Framework configs prefixed with `xh` (e.g., `xhActivityTrackingConfig`)
- **Timer/Cache names**: camelCase, unique within a service
- **Instance config env vars**: `APP_{APPCODE}_{KEY}` with camelCase converted to
  UPPER_SNAKE_CASE

### Logging & Exceptions

- Use `LogSupport` trait methods (`logDebug`, `logInfo`, `logWarn`, `logError`) — not raw SLF4J
  loggers. Use timed blocks (`withInfo`, `withDebug`) to auto-log elapsed time.
- Use `RoutineRuntimeException` for expected user-facing errors (logged at DEBUG, returns 400).
  Use `HttpException` subclasses (`NotAuthorizedException`, `NotFoundException`, etc.) for
  specific HTTP statuses. Don't use generic `RuntimeException` for expected errors.
- Don't wrap exceptions in try/catch in controller actions — `BaseController` handles all
  exceptions automatically. Let the framework pipeline handle logging and rendering.

### Services & Lifecycle

- All services extend `BaseService`. Use managed resource factories (`createCache`,
  `createCachedValue`, `createTimer`, `createIMap`) — not raw Hazelcast or Spring constructs.
- Always call `super.clearCaches()` when overriding `clearCaches()`. Declare
  `clearCachesConfigs` static list to auto-invalidate caches when soft configs change.
- Declare required configs in `ensureRequiredConfigsCreated()`, required prefs in
  `ensureRequiredPrefsCreated()`, and required roles in `ensureRequiredRolesCreated()` during
  bootstrap.
- Use `configService` typed getters (`getString`, `getInt`, `getBool`, `getMap`) — never query
  `AppConfig` domain directly.

### Controllers & Security

- Every controller endpoint must have an access annotation (`@AccessAll`,
  `@AccessRequiresRole`, etc.) on method or class — framework throws if missing.
- Use `renderJSON()` for all JSON responses — never Grails `render()`. Use
  `parseRequestJSON()` — never `request.JSON`.
- `AccessInterceptor` enforces roles **before** controller code runs, so role checks are not
  needed in action logic. For service-layer authorization, use `user.hasRole()`.

### GORM & Data Access

- Use `@ReadOnly` on all query-only service methods (skips dirty checking). Use
  `@Transactional` on all mutation methods.
- Avoid N+1 queries: eagerly load 1-to-1 associations with `fetch: 'join'` in mapping, use
  `batchSize` on lazy collections, use `findAllByFieldInList()` instead of looping with
  individual finders.
- Use `flush: true` only when subsequent code in the same transaction depends on persisted
  data. Never call `flush: true` inside loops — batch saves and flush once.
- Implement `JSONFormat` on all domain and POGO classes to control JSON serialization via
  `formatForJSON()`.

### Clustering & Caching

- Use `primaryOnly: true` on timers for tasks that should run once cluster-wide (scheduled
  refreshes, batch jobs). Without it, N instances = N executions.
- Use `replicate: true` on `Cache` and `CachedValue` for cluster-shared data. Use `IMap` for
  large datasets that should be partitioned across instances.
- Don't store non-serializable objects in Hazelcast structures (domain objects, closures) —
  use Maps or plain POGOs. Hazelcast is eventually consistent; expect brief windows of stale
  data.
- Call `refreshTimer.forceRun()` in `clearCaches()` to re-populate cached data immediately.

### HTTP & Email

- Reuse `JSONClient` instances for connection pooling — don't create one per request.
- Use `async: true` when sending email from request handlers to avoid blocking on SMTP.
- Set `xhEmailOverride` in dev/staging to redirect all outbound email for safety.

### Background & Async Work

- Don't access lazy-loaded GORM associations in timers or background threads — eagerly load
  within a session or use `withNewSession {}`.
- Always pass `username` when calling `trackService.track()` from timers (no request context).
- For WebSocket pushes: use `pushToAllChannels()` from primary-only/single-instance events,
  `pushToLocalChannels()` from replicated cache listeners (fires on every instance already).

## Code Style

- **Clear, descriptive naming** — Names should convey intent and read naturally. Be
  descriptive but not verbose (`configEntry`, not `c` or
  `theCurrentConfigurationEntryFromTheDatabase`).
- **Don't Repeat Yourself** — Extract shared logic into utilities, base class methods, or
  helpers. Balance DRY against readability — extract when a genuine, stable pattern exists,
  not prematurely.
- **Keep code concise** — Favor direct, compact expression over verbose or ceremonial
  patterns. Use Hoist's own utilities and base class methods to reduce boilerplate.
- **Groovy idioms** — Prefer Groovy's native features: map/list literals, GStrings, closures,
  `?.` safe navigation, `with {}` blocks. Use `null` as the "no value" sentinel. Avoid
  unnecessary type declarations where Groovy's type inference is clear.
- **Prefer Groovy collection methods** — Use `.collect()`, `.findAll()`, `.find()`,
  `.groupBy()`, `.collectEntries()`, `.sum()` etc. for collection operations. These are
  null-safe on the elements and more expressive than manual loops.

**Commit messages, PRs, and comments**: Do not hard-wrap lines in commit message bodies, pull
request descriptions, or issue/PR comments. Write each sentence or thought as a single
unwrapped line and let the viewing tool handle display wrapping.

## Key Dependencies

- **Grails 7** - Application framework (Spring Boot 3.5, GORM/Hibernate 5)
- **Hazelcast 5.6** - Distributed caching, pub/sub, multi-instance coordination
- **Jackson** - JSON serialization/parsing (via custom JSONSerializer/JSONParser wrappers)
- **Apache HttpClient 5** - HTTP client for external API calls
- **Apache POI 5** - Excel/spreadsheet generation
- **Micrometer** - Observable metrics with Prometheus and OTLP export
- **Kryo 5** - Fast serialization for Hazelcast distributed structures
- **Jasypt** - Encryption for `pwd`-type soft configuration values
- **Apache Directory API** - LDAP/Active Directory integration

## Reference Implementation: Toolbox

Toolbox is XH's example application showcasing Hoist patterns and components. It provides
real-world usage examples of services, controllers, configuration, and other framework
features — backed by hoist-core on the server side and hoist-react on the client side.

- **GitHub**: https://github.com/xh/toolbox
- **Local checkout**: `../toolbox` (relative to hoist-core root) — likely exists for Hoist
  library developers only.

When working on hoist-core library code or documentation, reference Toolbox for practical
examples of how features are used in applications. Note that the local checkout is specific to
the Hoist development environment and would not be available to general application developers
who have hoist-core as a dependency.
