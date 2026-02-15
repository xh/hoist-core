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
| `base-classes.md` | BaseService, BaseController, RestController, Cache, CachedValue, Timer, IMap | BaseService lifecycle (`init`, `destroy`, `parallelInit`), resource factories (`createCache`, `createCachedValue`, `createTimer`, `createIMap`), BaseController (`renderJSON`, `parseRequestJSON`, async support), RestController template-method CRUD (`doCreate`, `doList`, `doUpdate`, `doDelete`, `restTarget`) | Done |
| `request-flow.md` | HoistCoreGrailsPlugin, HoistFilter, UrlMappings, AccessInterceptor, BaseController | Full request lifecycle: plugin initialization → HoistFilter (auth gating, cluster readiness, exception catching) → UrlMappings routing → AccessInterceptor annotation checks → controller dispatch → JSON response | Draft |
| `authentication.md` | BaseAuthenticationService, BaseUserService, HoistUser, IdentityService, IdentitySupport | Abstract auth service contract, `allowRequest()` / `completeAuthentication()`, user lookup and HoistUser trait, IdentityService (current user, `getUser()`/`getAuthUser()`), impersonation support | Draft |
| `authorization.md` | BaseRoleService, DefaultRoleService, Role, RoleMember, AccessInterceptor, access annotations | Role assignment contract, `DefaultRoleService` (database-backed with admin UI), Role/RoleMember domains, `@AccessRequiresRole`/`@AccessRequiresAnyRole`/`@AccessRequiresAllRoles`/`@AccessAll` annotations, built-in roles (`HOIST_ADMIN`, `HOIST_ADMIN_READER`, `HOIST_IMPERSONATOR`, `HOIST_ROLE_MANAGER`) | Draft |

## Priority 2 — Core Features

Bread-and-butter features used by every Hoist application.

| Document | Source Files | Description | Status |
|----------|-------------|-------------|--------|
| `configuration.md` | AppConfig, ConfigService, ConfigDiffService, ConfigAdminController | AppConfig domain (typed values: `string\|int\|long\|double\|bool\|json\|pwd`), ConfigService typed getters, `clientVisible` flag, `pwd` encryption via Jasypt, required configs, `xhConfigChanged` event, config diffing across environments | Draft |
| `preferences.md` | Preference, UserPreference, PrefService, PrefDiffService, PreferenceAdminController | Preference definitions vs UserPreference values, PrefService lookups, `local` flag (browser-only prefs), required prefs, `xhPreferenceChanged` event, pref diffing across environments | Draft |
| `clustering.md` | ClusterService, ClusterConfig, Cache, CachedValue, IMap, ReplicatedMap, Topic, Timer | Hazelcast cluster lifecycle, distributed data structures (Cache, CachedValue, IMap, ReplicatedMap), pub/sub via Topic (`subscribeToTopic`), primary instance coordination, `primaryOnly` timers, naming convention `{ClassName}[{resourceName}]`, ClusterService admin stats | Draft |
| `activity-tracking.md` | TrackLog, TrackService, TrackLoggingService, ClientErrorEmailService, FeedbackEmailService | TrackLog domain, TrackService (`track()` endpoint, `xhTrackReceived` event), category/severity system, elapsed timing, client error email notifications, feedback email routing, `xhActivityTrackingConfig` | Draft |
| `json-handling.md` | JSONSerializer, JSONParser, JSONFormat, custom serializers, BaseController | Custom Jackson-based serialization (not Grails converters), `renderJSON()` / `parseRequestJSON()` in controllers, JSONFormat trait for domain/POGO classes, registering custom serializer modules via `JSONSerializer.registerModules()`, built-in serializers | Draft |

## Priority 3 — Infrastructure & Operations

Features that support production operations, integrations, and system health.

| Document | Source Files | Description | Status |
|----------|-------------|-------------|--------|
| `monitoring.md` | Monitor, MonitorResult, MonitoringService, MonitorDefinitionService, MonitorReportService | Monitor domain definitions, MonitorResult status model, MonitorDefinitionService pattern (app-provided), MonitoringService evaluation cycle, `MonitorStatusReport` email alerting, `xhMonitorConfig` | Draft |
| `websocket.md` | WebSocketService, HoistWebSocketHandler, HoistWebSocketChannel, HoistWebSocketConfigurer | WebSocketService cluster-aware push (`pushToChannel`), channel subscription model, Hazelcast topic relay for multi-instance delivery, session management, admin stats | Draft |
| `http-client.md` | JSONClient, BaseProxyService, HttpUtils | JSONClient (typed HTTP client with JSON serialization), BaseProxyService (proxying client requests to external APIs), HttpUtils helpers | Draft |
| `email.md` | EmailService, ClientErrorEmailService, FeedbackEmailService | EmailService (Grails mail plugin wrapper), config-driven filtering and overrides (`xhEmailFilter`, `xhEmailOverride`), support address configuration, client error and feedback email routing | Draft |
| `exception-handling.md` | ExceptionHandler, HttpException subclasses, RoutineException | Exception hierarchy (HttpException → NotAuthorizedException, NotFoundException, etc.), RoutineException (expected errors, logged at DEBUG), ExceptionHandler rendering, how exceptions map to HTTP status codes | Draft |
| `logging.md` | LogSupport, LogLevelService, LogReaderService, LogArchiveService, LogbackConfig | LogSupport trait (`logDebug`, `logInfo`, `logWarn`, `logError` with `withDebug`/`withInfo` timed blocks), dynamic log level configuration via LogLevelService, log viewing via LogReaderService, Logback configuration | Draft |

## Grails Platform

Guides to Grails framework concepts as used within Hoist applications. Not Hoist-specific API
docs — practical guides with emphasis on gotchas and optimization.

| Document | Source Files | Description | Status |
|----------|-------------|-------------|--------|
| `gorm-domain-objects.md` | All `grails-app/domain/` classes, ConfigService, PrefService, DefaultRoleUpdateService, TrackService, JsonBlobService, LogLevelService | GORM domain class anatomy (`mapping`, `constraints`, associations, lifecycle hooks), querying (dynamic finders, criteria, where queries, direct SQL), transaction management (`@ReadOnly`, `@Transactional`, `withTransaction`, `withNewSession`), associations and fetch strategies (`fetch: 'join'`, `batchSize`, `cascade`), N+1 query problem detection and mitigation, second-level cache (Hibernate + Hazelcast), circular dependencies, SQL logging, `formatForJSON()` convention | Done |

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
Do not remove the draft banner or update the roadmap status until the reviewer confirms approval.

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
in [`docs/README.md`](./README.md). Each entry should include a linked filename, a one-sentence
description, and a comma-separated list of key classes and concepts covered.

## Progress Notes

_Use this section to track discussions, decisions, and context between documentation sessions._

### 2026-02-13
- Created this roadmap document and the `docs/README.md` documentation index
- Established 22-document plan organized by feature area across 4 priority tiers
- Adapted hoist-react documentation conventions for hoist-core:
  - Feature-area docs (flat files in `docs/`) instead of per-package READMEs
  - Added "Source Files" guidance since features span Grails directories
  - Added "Application Implementation" section for features requiring app-level code
  - Added "Configuration" section for `xh`-prefixed AppConfig catalogs
  - Added "Client Integration" section for hoist-react cross-references
  - Code examples in Groovy (not TypeScript)
- Key structural decisions:
  - All docs live in `docs/` as flat files (not alongside source like hoist-react)
  - Organized by feature area, not by Grails directory convention
  - Root `README.md` untouched for now — will be slimmed down as feature docs are written
  - Consistent terminology with hoist-react docs
- Established three-phase review workflow (Planned → Draft → Done):
  - Drafts committed with a visible banner and `Draft` status in roadmap
  - Interactive review session required before promotion to `Done`
  - Matches the workflow used successfully in hoist-react docs

### 2026-02-13 (cont.) — Level 2 review and Priority 3 drafts
- Completed source-code-verified review of all 9 Priority 1+2 draft docs
- Key corrections applied across existing drafts:
  - **base-classes.md**: Fixed Cache/CachedValue `replicate` default (false, not true), corrected
    `parallelInit` description (static method, not property convention), added `doList` query guard
  - **request-flow.md**: Added whitelist check to `allowRequest()` description, fixed
    AccessInterceptor exception handling (self-contained, doesn't propagate to HoistFilter),
    expanded JSON error response structure to include `cause` and `isRoutine` fields
  - **authentication.md**: Corrected `allowRequest()` flow ordering (auth user check before
    whitelist), clarified internal exception handling, fixed IdentitySupport description (trait)
  - **authorization.md**: **CRITICAL** — fixed role inheritance direction (the `roles` field means
    "members of listed roles also get this role", not the other way around), added all 3 bootstrap
    admin roles, corrected Admin Console change propagation (immediate on local instance)
  - **configuration.md**: Clarified `externalValue()` behavior for `pwd` types, added `lastUpdatedBy`
    parameter to `setValue`, added `ConfigAdminController` to source files, noted conditional/async
    `beforeUpdate()` event firing
  - **preferences.md**: **CRITICAL** — removed incorrect claim about UserPreference deletion when
    value equals default (setter always saves unconditionally), fixed endpoint URL to `/xh/setPrefs`,
    added missing typed getters/setters for Long and Double
  - **clustering.md**: Fixed `replicate` default, corrected lifecycle (ApplicationReadyEvent, not
    BootStrap), removed fabricated `hazelcastGroupName`/`hazelcastAddresses` configs, added `createISet`
  - **activity-tracking.md**: Fixed file paths for email services (track/, not email/), corrected
    `maxDataLength` default (2000, not 50000), fixed ClientErrorEmailService description (uses timer,
    not topic subscription), added missing config keys
  - **json-handling.md**: Noted Java source files (.java not .groovy), corrected JSONFormatCached as
    parallel to JSONFormat (not extending it), expanded ThrowableSerializer output description
- Wrote 6 new Priority 3 draft docs: monitoring, websocket, http-client, email, exception-handling,
  logging — all source-code-verified with self-review corrections applied
- Updated Priority 3 status from Planned → Draft in roadmap table
- All 15 docs (P1+P2+P3) now at Draft status, ready for interactive review

### 2026-02-14 — GORM & Domain Objects documentation
- Created `gorm-domain-objects.md` — a practical guide to GORM as used within Hoist applications
- Not Hoist-specific API documentation, but a Grails platform guide covering domain class anatomy,
  querying patterns, transaction management, association strategies, caching, and common pitfalls
- Source-code-verified against all 9 Hoist Core domain classes and key services
- Includes patterns observed in production Hoist applications (schema separation, `withNewSession`
  for cache priming, `withNewTransaction` for independent commits, direct SQL via `groovy.sql.Sql`,
  stub caches for N+1 mitigation)
- Added "Grails Platform" section to both README.md index and README-ROADMAP.md
- Doc placed in its own "Grails Platform" section rather than a priority tier, as it covers
  foundational Grails concepts rather than a specific Hoist feature area
