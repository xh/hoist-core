# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Hoist-core is the server-side component of the Hoist web application development toolkit, built by
Extremely Heavy Industries (xh.io). It is a **Grails 7 plugin** (not a standalone app) published as
`io.xh:hoist-core` and consumed by Grails application projects. The client-side counterpart is
[hoist-react](https://github.com/xh/hoist-react).

- **Language**: Groovy 4 / Java 17
- **Framework**: Grails 7.0.5 (Spring Boot 3.5, Hibernate 5, GORM)
- **Clustering**: Hazelcast 5.6 for distributed caching, pub/sub, and multi-instance coordination
- **Package root**: `io.xh.hoist`

## Build Commands

```bash
./gradlew compileGroovy          # Compile
./gradlew check                  # Run checks/tests
./gradlew publishHoistCore       # Publish to repo.xh.io (requires credentials)
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
HTTP requests pass through `HoistFilter` (servlet filter) which ensures the cluster is running and
delegates to `BaseAuthenticationService.allowRequest()`. Requests then route via `UrlMappings` to
controllers extending `BaseController` (general endpoints) or `RestController` (CRUD operations).

### Service Layer
All framework and application services extend `BaseService`, which provides:
- **Lifecycle**: `init()` for startup, `destroy()` for shutdown, `parallelInit()` for batch startup
- **Distributed resources** (Hazelcast-backed): `createCache()`, `createCachedValue()`, `createTimer()`, `createIMap()`, `createReplicatedMap()`
- **Event systems**: `subscribe()` (local Grails events), `subscribeToTopic()` (cluster-wide pub/sub)
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

Built-in roles: `HOIST_ADMIN`, `HOIST_ADMIN_READER`, `HOIST_IMPERSONATOR`, `HOIST_ROLE_MANAGER`.

### Clustering (Hazelcast)
`ClusterService` manages multi-instance coordination. The primary instance (oldest member) handles
primary-only tasks (e.g., timers with `primaryOnly: true`). Distributed data structures (IMap,
ReplicatedMap, Topic) are named using the pattern `{ClassName}[{resourceName}]`.

### Soft Configuration
`AppConfig` domain objects store typed config values (`string|int|long|double|bool|json|pwd`) in
the database. `ConfigService` provides typed getters. Configs can be marked `clientVisible` for
the JS client. The `pwd` type stores values encrypted via Jasypt.

### JSON Handling
Custom Jackson-based `JSONSerializer` and `JSONParser` — not Grails' default JSON converters.
Controllers use `renderJSON()` and `parseRequestJSON()`. Custom serializers are registered via
`JSONSerializer.registerModules()`.

## Key Conventions

- **Logging**: Services and controllers implement `LogSupport` — use `logDebug()`, `logInfo()`,
  `logWarn()`, `logError()` (not raw SLF4J loggers)
- **Exception handling**: Use `HttpException` subclasses (`NotAuthorizedException`,
  `NotFoundException`, etc.) for HTTP errors. `RoutineException` for expected user-facing errors
  (logged at DEBUG, not ERROR)
- **Event names**: Prefixed with `xh` (e.g., `xhConfigChanged`, `xhTrackReceived`)
- **Config names**: Framework configs prefixed with `xh` (e.g., `xhActivityTrackingConfig`)
- **Timer/Cache names**: camelCase, unique within a service