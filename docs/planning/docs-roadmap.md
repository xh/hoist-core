# Documentation Roadmap

This document tracks planned documentation for hoist-core feature areas. The primary goal is to
provide AI coding assistants with rich context for working with hoist-core — both for framework
development and application development. A secondary goal is improving human-readable documentation
for all developers.

Unlike hoist-react (where docs live alongside each package as `README.md` files), hoist-core
features span multiple Grails convention directories (`controllers/`, `services/`, `domain/`,
`src/`). All docs therefore live in `docs/` as flat files organized by **feature area** — e.g.
`docs/configuration.md`, `docs/authentication.md`.

## Priority 1 — Core Framework

These documents cover the foundational patterns that everything else builds on. Understanding these
is essential for working effectively with any part of hoist-core.

| Document | Source Files | Description | Status |
|----------|-------------|-------------|--------|
| [`base-classes.md`](../base-classes.md) | BaseService, BaseController, RestController, Cache, CachedValue, Timer, IMap | BaseService lifecycle (`init`, `destroy`, `parallelInit`), resource factories (`createCache`, `createCachedValue`, `createTimer`, `createIMap`), BaseController (`renderJSON`, `parseRequestJSON`, async support), RestController template-method CRUD (`doCreate`, `doList`, `doUpdate`, `doDelete`, `restTarget`) | Done |
| [`request-flow.md`](../request-flow.md) | HoistCoreGrailsPlugin, HoistFilter, UrlMappings, AccessInterceptor, BaseController | Full request lifecycle: plugin initialization → HoistFilter (auth gating, instance readiness, exception catching) → UrlMappings routing → AccessInterceptor annotation checks → controller dispatch → JSON response | Done |
| [`authentication.md`](../authentication.md) | BaseAuthenticationService, BaseUserService, HoistUser, IdentityService, IdentitySupport | Abstract auth service contract, `allowRequest()` / `completeAuthentication()`, user lookup and HoistUser trait, IdentityService (current user, `getUser()`/`getAuthUser()`), impersonation support | Done |
| [`authorization.md`](../authorization.md) | BaseRoleService, DefaultRoleService, Role, RoleMember, AccessInterceptor, access annotations | Role assignment contract, `DefaultRoleService` (database-backed with admin UI), Role/RoleMember domains, `@AccessRequiresRole`/`@AccessRequiresAnyRole`/`@AccessRequiresAllRoles`/`@AccessAll` annotations, built-in roles (`HOIST_ADMIN`, `HOIST_ADMIN_READER`, `HOIST_IMPERSONATOR`, `HOIST_ROLE_MANAGER`) | Done |

## Priority 2 — Core Features

Bread-and-butter features used by every Hoist application.

| Document | Source Files | Description | Status |
|----------|-------------|-------------|--------|
| [`configuration.md`](../configuration.md) | AppConfig, ConfigService, ConfigDiffService, ConfigAdminController | AppConfig domain (typed values: `string\|int\|long\|double\|bool\|json\|pwd`), ConfigService typed getters, `clientVisible` flag, `pwd` encryption via Jasypt, required configs, `xhConfigChanged` event, config diffing across environments | Done |
| [`preferences.md`](../preferences.md) | Preference, UserPreference, PrefService, PrefDiffService, PreferenceAdminController | Preference definitions vs UserPreference values, PrefService lookups, required prefs, pref diffing across environments | Done |
| [`clustering.md`](../clustering.md) | ClusterService, ClusterConfig, Cache, CachedValue, IMap, ReplicatedMap, Topic, Timer | Hazelcast cluster lifecycle, distributed data structures (Cache, CachedValue, IMap, ReplicatedMap), pub/sub via Topic (`subscribeToTopic`), primary instance coordination, `primaryOnly` timers, naming convention `{ClassName}[{resourceName}]`, ClusterService admin stats | Draft |
| [`activity-tracking.md`](../activity-tracking.md) | TrackLog, TrackService, TrackLoggingService, ClientErrorEmailService, FeedbackEmailService | TrackLog domain, TrackService (`track()` endpoint, `xhTrackReceived` event), category/severity system, elapsed timing, client error email notifications, feedback email routing, `xhActivityTrackingConfig` | Draft |
| [`json-handling.md`](../json-handling.md) | JSONSerializer, JSONParser, JSONFormat, custom serializers, BaseController | Custom Jackson-based serialization (not Grails converters), `renderJSON()` / `parseRequestJSON()` in controllers, JSONFormat trait for domain/POGO classes, registering custom serializer modules via `JSONSerializer.registerModules()`, built-in serializers | Draft |

## Priority 3 — Infrastructure & Operations

Features that support production operations, integrations, and system health.

| Document | Source Files | Description | Status |
|----------|-------------|-------------|--------|
| [`monitoring.md`](../monitoring.md) | Monitor, MonitorResult, MonitoringService, MonitorDefinitionService, MonitorReportService | Monitor domain definitions, MonitorResult status model, MonitorDefinitionService pattern (app-provided), MonitoringService evaluation cycle, `MonitorStatusReport` email alerting, `xhMonitorConfig` | Draft |
| [`websocket.md`](../websocket.md) | WebSocketService, HoistWebSocketHandler, HoistWebSocketChannel, HoistWebSocketConfigurer | WebSocketService cluster-aware push (`pushToChannel`), channel subscription model, Hazelcast topic relay for multi-instance delivery, session management, admin stats | Draft |
| [`http-client.md`](../http-client.md) | JSONClient, BaseProxyService, HttpUtils | JSONClient (typed HTTP client with JSON serialization), BaseProxyService (proxying client requests to external APIs), HttpUtils helpers | Draft |
| [`email.md`](../email.md) | EmailService, ClientErrorEmailService, FeedbackEmailService | EmailService (Grails mail plugin wrapper), config-driven filtering and overrides (`xhEmailFilter`, `xhEmailOverride`), support address configuration, client error and feedback email routing | Draft |
| [`exception-handling.md`](../exception-handling.md) | ExceptionHandler, HttpException subclasses, RoutineException | Exception hierarchy (HttpException → NotAuthorizedException, NotFoundException, etc.), RoutineException (expected errors, logged at DEBUG), ExceptionHandler rendering, how exceptions map to HTTP status codes | Draft |
| [`logging.md`](../logging.md) | LogSupport, LogLevelService, LogReaderService, LogArchiveService, LogbackConfig | LogSupport trait (`logDebug`, `logInfo`, `logWarn`, `logError` with `withDebug`/`withInfo` timed blocks), dynamic log level configuration via LogLevelService, log viewing via LogReaderService, Logback configuration | Done |
| [`metrics.md`](../metrics.md) | MetricsService, MonitorMetricsService, TrackMetricsService, CompositeMeterRegistry | Micrometer-based observable metrics with Prometheus and OTLP export, monitor and track metric bridges, `xhMetricsConfig` | Done |

## Application Development

Guides to building, structuring, and deploying Hoist applications.

| Document | Source Files | Description | Status |
|----------|-------------|-------------|--------|
| [`application-structure.md`](../application-structure.md) | `build.gradle`, `gradle.properties`, `settings.gradle`, `.env.template`, `grails-app/init/`, `grails-app/conf/`, `client-app/`, `docker/` | Standard Hoist application repository layout — root directory structure, Gradle build configuration, server-side Grails conventions (init files, conf, controllers, services, domain), client-side React/TypeScript conventions (Bootstrap.ts, entry points, AppModel/AppComponent, shared code), Docker deployment (Nginx + Tomcat), local development workflow | Draft |

## Grails Platform

Guides to Grails framework concepts as used within Hoist applications. Not Hoist-specific API
docs — practical guides with emphasis on gotchas and optimization.

| Document | Source Files | Description | Status |
|----------|-------------|-------------|--------|
| [`gorm-domain-objects.md`](../gorm-domain-objects.md) | All `grails-app/domain/` classes, ConfigService, PrefService, DefaultRoleUpdateService, TrackService, JsonBlobService, LogLevelService | GORM domain class anatomy (`mapping`, `constraints`, associations, lifecycle hooks), querying (dynamic finders, criteria, where queries, direct SQL), transaction management (`@ReadOnly`, `@Transactional`, `withTransaction`, `withNewSession`), associations and fetch strategies (`fetch: 'join'`, `batchSize`, `cascade`), N+1 query problem detection and mitigation, second-level cache (Hibernate + Hazelcast), circular dependencies, SQL logging, `formatForJSON()` convention | Done |

## Priority 4 — Supporting Features

Smaller or more specialized features. Important but lower priority for initial documentation.

| Document | Source Files | Description | Status |
|----------|-------------|-------------|--------|
| `data-filtering.md` | Filter, FieldFilter, CompoundFilter (in `data/filter/`) | Server-side filter system mirroring the client-side hoist-react Filter hierarchy, field-level and compound filters, JSON serialization for client-server roundtrip | Planned |
| `utilities.md` | Timer, DateTimeUtils, StringUtils, Utils, InstanceConfigUtils, AsyncUtils | Timer (polling, `primaryOnly`, Hazelcast-backed), DateTimeUtils, StringUtils, general Utils, InstanceConfigUtils (external config loading), AsyncUtils | Planned |
| `jsonblob.md` | JsonBlob, JsonBlobService, JsonBlobDiffService, XhController (blob endpoints) | JsonBlob domain (backing store for ViewManager and other client state), CRUD via XhController endpoints, token-based access, type/name/owner metadata, archival, diffing across environments | Planned |
| `ldap.md` | LdapService, LdapPerson, LdapGroup, LdapObject | LdapService (Active Directory / LDAP integration), LdapPerson and LdapGroup lookups, connection configuration | Planned |
| `environment.md` | EnvironmentService, AppEnvironment, InstanceConfigUtils, Application, BootStrap | AppEnvironment enum, EnvironmentService (runtime environment info), InstanceConfigUtils (external config files), Grails environment vs Hoist environment distinction, environment polling for client | Planned |
| `admin-endpoints.md` | XhController, admin controllers, AlertBannerService, ViewService, ServiceManagerService | XhController primary endpoints (auth, config, prefs, tracking, blobs, export, environment), admin controller catalog, AlertBannerService, ViewService, ServiceManagerService, connection pool and memory monitoring | Planned |

## Documentation Guidelines

### Review Workflow

Each document progresses through three statuses tracked in the tables above:

1. **Planned** — Document is scoped but not yet written
2. **Draft** — Initial draft is written and committed. The doc file itself includes a
   `> **Status: DRAFT** — This document is awaiting review...` banner at the top
3. **Done** — Draft has been interactively reviewed, revisions applied, and the draft banner
   removed. The doc is considered complete and authoritative

The draft → done transition requires an interactive review session. During review, expect
discussion of accuracy, completeness, tone, code examples, and coverage of edge cases.
**Only a human XH developer can mark a document as done.** Do not remove the draft banner or
update the roadmap status until the human reviewer explicitly requests it — AI-driven review
and corrections alone are not sufficient to promote a doc out of draft.

### Document Template

Each feature-area document should follow this general structure. Not every section applies to
every feature — use judgment and include what's useful.

1. **Overview** — What the feature does, why it exists, and where it fits in the Hoist architecture
2. **Architecture** — Key classes, their relationships, and which Grails directories they span.
   Include source file paths since features are spread across `controllers/`, `services/`,
   `domain/`, and `src/`
3. **Key Classes** — Detailed coverage of the primary classes with API highlights and code examples
4. **Configuration** — Catalog of relevant `xh`-prefixed AppConfigs with types and descriptions
5. **Application Implementation** — For features requiring app-level code (auth, roles, monitors):
   what apps must implement, with examples
6. **Common Patterns** — Groovy code examples for typical usage
7. **Client Integration** — How this feature connects to hoist-react, with links to corresponding
   client-side docs where available
8. **Common Pitfalls** — Significant anti-patterns with `###` sub-headers for each

### Source File References

Since features span multiple Grails directories, always include a source files table or list
early in each doc. Example:

```markdown
### Source Files

| File | Location | Role |
|------|----------|------|
| `ConfigService` | `grails-app/services/` | Primary service — typed getters, event publishing |
| `AppConfig` | `grails-app/domain/` | GORM domain class — database-backed config entries |
| `ConfigAdminController` | `grails-app/controllers/admin/` | Admin console CRUD endpoints |
```

### Tone and Content

- Write for both AI assistants and human developers
- Prioritize patterns and relationships over exhaustive API documentation
- Include runnable Groovy code examples
- Explain "why" not just "what"
- Reference specific source files where helpful
- Keep examples practical and representative of real usage
- **Sample application names must not appear in documentation.** Code examples may be drawn from
  sample applications for patterns and structure, but must be genericized before inclusion. Use
  generic financial-domain terms (e.g. `Portfolio`, `Trade`, `Position`, `Trader`) rather than
  client-specific class names, table names, or service names. The publicly available Toolbox demo
  app is the *exception* - it CAN be freely mentioned by name and its code referenced directly.

### Communicating Anti-patterns

Use a two-part approach for documenting things to avoid:

1. **Inline warnings** — Use `**Avoid:**` prefix for brief notes near relevant content
2. **Common Pitfalls section** — Dedicated `## Common Pitfalls` section at the end with `###`
   sub-headers for each individual pitfall

For code examples showing correct vs incorrect approaches, use ✅/❌ markers:

```groovy
// ✅ Do: Use typed getter
String region = configService.getString('myAppRegion')

// ❌ Don't: Access raw value without type coercion
def region = configService.getObject('myAppRegion').value
```

### Terminology Conventions

Use consistent terminology with hoist-react documentation:

| Term | Usage |
|------|-------|
| **Soft configuration** | Database-backed AppConfig values (not code-level config) |
| **Preferences** | User-specific settings (Preference + UserPreference) |
| **Activity tracking** | TrackLog-based usage and performance logging |
| **JsonBlob** | Generic JSON storage backing ViewManager and other client state |
| **WebSocket push** | Server-initiated messages to connected clients |
| **Cluster** | Multi-instance Hazelcast coordination |
| **Primary instance** | Oldest cluster member, handles `primaryOnly` tasks |

### Keeping the Documentation Index in Sync

When a feature-area document is completed, add a corresponding entry to the appropriate section
in [`docs/README.md`](../README.md). Each entry should include a linked filename, a one-sentence
description, and a comma-separated list of key classes and concepts covered.

### Progress Tracking Convention

Roadmap files use a two-file pattern to keep planning documents lean while preserving
detailed history:

- **Roadmap** (`docs-roadmap.md`): Lean reference document with status tables,
  guidelines, and a thematic progress summary. This is the primary file agents should read.
- **Progress Log** (`docs-roadmap-log.md`): Append-only chronological session notes
  with full detail. Maintained as a historical record — consult only when investigating
  specific past decisions or context.

After a work session, append detailed notes to the log file. Update the roadmap's progress
summary only when new conventions or significant milestones are reached.

## Progress Summary

_For detailed session-by-session notes, see [docs-roadmap-log.md](./docs-roadmap-log.md)._

### Status Overview
- **Priority 1 (Core Framework):** All 4 docs Done (base-classes, request-flow, authentication,
  authorization)
- **Priority 2 (Core Features):** 2 Done (configuration, preferences), 3 in Draft (clustering,
  activity-tracking, json-handling)
- **Priority 3 (Infrastructure):** 2 Done (logging, metrics), 5 in Draft (monitoring, websocket,
  http-client, email, exception-handling)
- **Application Development:** application-structure in Draft
- **Grails Platform:** gorm-domain-objects Done
- **Priority 4 (Supporting Features):** All 6 docs still Planned
- **Documentation index** (`docs/README.md`) created and maintained alongside feature docs

### Key Decisions
Conventions established during the documentation effort and not already captured in the
Documentation Guidelines above:

- Created `docs/README.md` as the primary documentation index — `AGENTS.md` no longer hosts
  documentation tables, instead pointing to the index with a compact directive
- All docs live in `docs/` as flat files organized by feature area (not alongside source
  like hoist-react), since features span multiple Grails convention directories
- "Grails Platform" section created for non-Hoist-specific guides (GORM, etc.) — these sit
  outside the priority tiers
- Source-code-verified self-review applied to all drafts before committing — caught critical
  errors in role inheritance direction and preference deletion behavior

### Current Focus
- Completing interactive reviews of remaining Draft docs (P2/P3)
- Priority 4 docs remain Planned — will be drafted after P2–P3 reviews complete
