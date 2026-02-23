# Hoist Core Documentation Index

This is the primary catalog for all hoist-core documentation. It indexes feature-area docs,
upgrade notes, and supporting guides — with descriptions and key topics to support fast,
targeted retrieval.

## How to Use This Index

**AI coding agents:** Scan the tables below and match the **Key Topics** column against the APIs,
classes, or patterns you're working with. Use the [Quick Reference by Task](#quick-reference-by-task)
table to map natural-language goals to the right document.

**Application developers:** Navigate by feature area to find architecture, configuration, and usage
patterns. Start with the [Core Framework](#core-framework) table for foundational concepts, then
drill into [Core Features](#core-features) or [Infrastructure](#infrastructure--operations).

**Library developers:** In addition to the docs below, see
[`/AGENTS.md`](../AGENTS.md) for coding conventions, architecture patterns, and code style guidance.
See [`planning/docs-roadmap.md`](./planning/docs-roadmap.md) for documentation coverage tracking
and conventions.

## Quick Reference by Task

| If you need to... | Start here |
|---|---|
| Understand BaseService lifecycle and resource factories | [`base-classes.md`](./base-classes.md) |
| Understand BaseController / RestController patterns | [`base-classes.md`](./base-classes.md) |
| Trace an HTTP request through the framework | [`request-flow.md`](./request-flow.md) |
| Implement authentication in your app | [`authentication.md`](./authentication.md) |
| Set up roles and access control | [`authorization.md`](./authorization.md) |
| Work with AppConfig (soft configuration) | [`configuration.md`](./configuration.md) |
| Work with user preferences | [`preferences.md`](./preferences.md) |
| Understand Hazelcast clustering and distributed resources | [`clustering.md`](./clustering.md) |
| Add activity tracking or review track logs | [`activity-tracking.md`](./activity-tracking.md) |
| Serialize or parse JSON | [`json-handling.md`](./json-handling.md) |
| Set up application monitors | [`monitoring.md`](./monitoring.md) |
| Push messages to clients via WebSocket | [`websocket.md`](./websocket.md) |
| Make HTTP calls to external services | [`http-client.md`](./http-client.md) |
| Send emails from your app | [`email.md`](./email.md) |
| Understand the exception hierarchy | [`exception-handling.md`](./exception-handling.md) |
| Configure logging or read logs | [`logging.md`](./logging.md) |
| Apply server-side data filters | [`data-filtering.md`](./data-filtering.md) |
| Work with JsonBlobs (ViewManager backing store) | [`jsonblob.md`](./jsonblob.md) |
| Integrate with LDAP / Active Directory | [`ldap.md`](./ldap.md) |
| Understand environments and instance config | [`environment.md`](./environment.md) |
| Find admin console endpoints | [`admin-endpoints.md`](./admin-endpoints.md) |
| Use Timer, DateTimeUtils, or other utilities | [`utilities.md`](./utilities.md) |
| Work with GORM domain objects and Hibernate | [`gorm-domain-objects.md`](./gorm-domain-objects.md) |
| Upgrade to a new major hoist-core version | [Upgrade Notes](#upgrade-notes) |

## Feature Documentation

### Core Framework

Foundational patterns that everything else builds on.

| Document | Description | Key Topics |
|----------|-------------|------------|
| [`base-classes.md`](./base-classes.md) | Base classes for services and controllers — lifecycle, resource factories, CRUD patterns | BaseService, `init`/`destroy`, `createCache`, `createCachedValue`, `createTimer`, `createIMap`, BaseController, `renderJSON`, `parseRequestJSON`, RestController, `doCreate`/`doList`/`doUpdate`/`doDelete` |
| [`request-flow.md`](./request-flow.md) | How an HTTP request flows through the Hoist framework | HoistCoreGrailsPlugin, HoistFilter, UrlMappings, AccessInterceptor, controller dispatch, JSON response |
| [`authentication.md`](./authentication.md) | Authentication service contract and user identity | BaseAuthenticationService, BaseUserService, HoistUser, IdentityService, impersonation |
| [`authorization.md`](./authorization.md) | Role-based access control and controller security annotations | BaseRoleService, DefaultRoleService, Role, RoleMember, `@AccessRequiresRole`, `@AccessAll`, built-in roles |

### Core Features

Bread-and-butter features used by every Hoist application.

| Document | Description | Key Topics |
|----------|-------------|------------|
| [`configuration.md`](./configuration.md) | Database-backed soft configuration with typed values | AppConfig, ConfigService, `clientVisible`, `pwd` encryption, `xhConfigChanged`, required configs |
| [`preferences.md`](./preferences.md) | User-specific settings and preference management | Preference, UserPreference, PrefService, `local` flag, required prefs |
| [`clustering.md`](./clustering.md) | Hazelcast-based multi-instance coordination and distributed data structures | ClusterService, Cache, CachedValue, IMap, ReplicatedMap, Topic, `primaryOnly` timers |
| [`activity-tracking.md`](./activity-tracking.md) | Usage and performance logging with email notifications | TrackLog, TrackService, categories, elapsed timing, client error emails, feedback emails |
| [`json-handling.md`](./json-handling.md) | Jackson-based JSON serialization and parsing | JSONSerializer, JSONParser, JSONFormat, custom serializer modules, `renderJSON`, `parseRequestJSON` |

### Infrastructure & Operations

Features supporting production operations, integrations, and system health.

| Document | Description | Key Topics |
|----------|-------------|------------|
| [`monitoring.md`](./monitoring.md) | Application health monitoring with configurable checks and email alerting | Monitor, MonitorResult, MonitoringService, MonitorDefinitionService, email alerts |
| [`websocket.md`](./websocket.md) | Cluster-aware server push to connected clients | WebSocketService, HoistWebSocketHandler, HoistWebSocketChannel, channel subscriptions |
| [`http-client.md`](./http-client.md) | HTTP client for external API calls and request proxying | JSONClient, BaseProxyService, HttpUtils |
| [`email.md`](./email.md) | Email sending with config-driven filtering and overrides | EmailService, `xhEmailFilter`, `xhEmailOverride`, support address config |
| [`exception-handling.md`](./exception-handling.md) | Exception hierarchy and error rendering | HttpException, RoutineException, ExceptionHandler, HTTP status mapping |
| [`logging.md`](./logging.md) | Logging infrastructure with dynamic configuration | LogSupport, `logDebug`/`logInfo`/`logWarn`/`logError`, LogLevelService, LogReaderService |

### Grails Platform

Guides to Grails framework concepts as used within Hoist applications.

| Document | Description | Key Topics |
|----------|-------------|------------|
| [`gorm-domain-objects.md`](./gorm-domain-objects.md) | GORM domain classes, querying, transactions, caching, associations, and performance optimization | Domain classes, `@Transactional`, `@ReadOnly`, second-level cache, N+1 queries, fetch strategies, SQL logging |

### Supporting Features

Smaller or more specialized features.

| Document | Description | Key Topics |
|----------|-------------|------------|
| [`data-filtering.md`](./data-filtering.md) | Server-side filter system mirroring hoist-react's client-side filters | Filter, FieldFilter, CompoundFilter, JSON roundtrip |
| [`utilities.md`](./utilities.md) | Timers, date/string utilities, and async helpers | Timer, DateTimeUtils, StringUtils, Utils, InstanceConfigUtils, AsyncUtils |
| [`jsonblob.md`](./jsonblob.md) | Generic JSON storage backing ViewManager and other client state | JsonBlob, JsonBlobService, token-based access, type/name/owner metadata |
| [`ldap.md`](./ldap.md) | LDAP / Active Directory integration for user and group lookups | LdapService, LdapPerson, LdapGroup |
| [`environment.md`](./environment.md) | Runtime environment detection and external configuration | EnvironmentService, AppEnvironment, InstanceConfigUtils, Grails vs Hoist environments |
| [`admin-endpoints.md`](./admin-endpoints.md) | Admin console endpoints and supporting services | XhController, admin controllers, AlertBannerService, ViewService, ServiceManagerService |

## Upgrade Notes

Step-by-step guides for upgrading applications across major hoist-core versions, with
breaking changes, before/after code examples, and verification checklists.

> **Always check the latest version of these notes on the
> [`develop` branch on GitHub](https://github.com/xh/hoist-core/tree/develop/docs/upgrade-notes).**
> Upgrade notes are refined after release as developers report issues and new patterns emerge.

| Version | Key Changes |
|---------|-------------|
| [v36.0.0](./upgrade-notes/v36-upgrade-notes.md) | Cluster-aware WebSockets, new `@AccessRequiresXXX` annotations, `@Access` deprecated |
| [v35.0.0](./upgrade-notes/v35-upgrade-notes.md) | CacheEntry generic key type, TrackLog `clientAppCode`, POI 5.x |
| [v34.0.1](./upgrade-notes/v34-upgrade-notes.md) | Grails 7, Gradle 8, Tomcat 10, Jakarta EE |

## Additional Resources

- [`/AGENTS.md`](../AGENTS.md) — AI coding assistant guidance: architecture patterns, coding
  conventions, and key dependencies
- [`planning/docs-roadmap.md`](./planning/docs-roadmap.md) — Documentation coverage tracking,
  conventions, and guidelines
- [`/CHANGELOG.md`](../CHANGELOG.md) — Version history and release notes
- [`/README.md`](../README.md) — Project overview with feature tables and source code links
- [hoist-react docs](https://github.com/xh/hoist-react/tree/develop/docs) — Client-side
  counterpart documentation
