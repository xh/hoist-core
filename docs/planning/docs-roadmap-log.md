# Documentation Roadmap — Progress Log

> This file contains detailed, chronological session notes for the documentation effort
> tracked in [docs-roadmap.md](./docs-roadmap.md). It is maintained as a historical record.
> **For current status, guidelines, and key decisions, read the roadmap instead.**

## Progress Notes

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

### 2026-02-14 (cont.) — Interactive reviews: base-classes, gorm-domain-objects, request-flow
- Reviewed `base-classes.md` — fixed inaccuracies, expanded API coverage, marked Done
- Reviewed `gorm-domain-objects.md` — clarified pitfalls, added admin cache tools, marked Done
- Reviewed `request-flow.md` — key corrections:
  - Replaced "Hazelcast cluster readiness" with "instance readiness" throughout — `ensureRunning()`
    checks instance lifecycle state, not Hazelcast specifically
  - Clarified Hazelcast init is required even for single-instance deployments
  - Added `allowRequest()` as a 4th exception handling layer with distinct security behavior (opaque
    error to unverified clients, no JSON body)
  - Fixed JSON error filtering description (falsy values, not just null)
  - Added `(.$format)?` to standard UrlMappings pattern
  - Specified WebSocket bypass checks both header and URI path
  - Marked `DefaultController` as app-provided (not part of hoist-core)
  - Marked Done
- Reviewed `authentication.md` — key corrections:
  - Fixed claim that "IdentityService never creates sessions" — `noteUserAuthenticated()` does
    create sessions, but only for verified users; all other access uses `getSession(false)`
  - Fixed claim that all IdentityService methods return null outside request context — ThreadLocal
    fallback (`threadUsername`/`threadAuthUsername`) propagates identity during cluster task execution
  - Simplified flow diagram to match actual code structure (single `||` check for auth user or
    whitelist, not sequential decisions)
  - Added `isWhitelist(HttpServletRequest)` protected method as override point for custom whitelist
    logic beyond mutating the URI list
  - Named `xhEnableImpersonation` config key (was only described generically)
  - Documented both `getClientConfig()` response shapes: `{user, roles}` normally vs.
    `{apparentUser, apparentUserRoles, authUser, authUserRoles}` during impersonation
  - Added `formatForJSON()` / `JSONFormat` to HoistUser description
  - Clarified login/logout flow through IdentityService (controllers call IdentityService, which
    delegates to AuthenticationService and handles session cleanup)
  - Marked Done
- Reviewed `authorization.md` — key corrections:
  - Fixed cluster propagation claim: Admin Console changes propagate immediately to all instances
    via replicated `CachedValue`, not delayed until the next timer cycle. Timer interval governs
    external directory group membership refresh only
  - Added 3 missing source files: `DefaultRoleAdminService`, `DefaultRoleUpdateService`,
    `RoleAdminController`
  - Added `Customization Points` section documenting `userAssignmentSupported`,
    `directoryGroupsSupported`, `directoryGroupsDescription`, and `doLoadUsersForDirectoryGroups`
    override points
  - Added alternative `resources.groovy` bean registration approach for no-customization usage
  - Fixed `Role.members` type from `List<RoleMember>` to `Set<RoleMember>` (GORM `hasMany` default)
  - Clarified bootstrap admin restriction is code-enforced (`isLocalDevelopment && !isProduction`),
    not merely advisory
  - Noted `HOIST_ROLE_MANAGER` is intentionally independent from `HOIST_ADMIN` — admin status does
    not automatically grant role management capability
  - Renamed "Stale role cache" pitfall to "Stale directory group memberships" to accurately reflect
    the actual caching concern
  - Improved framing of `DefaultRoleService` vs custom `BaseRoleService`: DefaultRoleService is a
    production-ready, self-contained default; custom implementations are for apps/customers with an
    existing external role source (JWT claims, Entra ID, custom APIs)
  - Reverted premature Done status — clarified in Review Workflow that only a human XH developer
    can promote a doc out of Draft. AI-driven review alone is not sufficient
  - Awaiting human sign-off

### 2026-02-16 — Interactive review: configuration
- Reviewed `configuration.md` against all 7 referenced source files
- Key corrections and additions:
  - Fixed env var naming to document hyphen-to-underscore replacement (app codes with dashes)
  - Expanded Reactive Config Usage section: `clearCachesConfigs` now leads as the primary pattern
    with a full CachedValue example showing lazy invalidation, followed by manual `subscribeToTopic`
    for custom handling
  - Fixed Timer interval config example: added `intervalUnits: SECONDS`, noted config must be `int`
    type looked up via `configService.getInt()`
  - Added "When to Use Soft Configs" section near top — covers avoiding magic numbers,
    per-environment tuning (different config DBs per environment), runtime experimentation
  - Added opening paragraph emphasizing config system as widely used and important
  - Added "Externalizing Magic Numbers" as first common pattern
  - Reworked naming conventions: apps don't need app-specific prefix (sole consumers of their own
    configs), use camelCase, include units in names where relevant
  - Trimmed ConfigDiffService to brief internal-service note
  - Added pitfalls: ambiguous config names, instance configs when soft configs suffice, instance
    configs in `application.groovy`
  - Strengthened `ensureRequiredConfigsCreated` guidance: declare all long-lived configs, not just
    strictly required ones — creates inventory, ensures fresh DBs have viewable entries
  - Rephrased all pitfall headings to "Avoid..."/"Don't..." to prevent misinterpretation as
    instructions
- Marked Done, DRAFT banner removed
- First Priority 2 doc complete

### 2026-02-16 — Interactive review: authorization (human sign-off)
- Reviewed `authorization.md` against all 13 referenced source files — no major factual errors found
- Key corrections and additions:
  - Fixed Customization Points section: removed "protected" qualifier — 3 of 4 override points
    (`getUserAssignmentSupported`, `getDirectoryGroupsSupported`, `getDirectoryGroupsDescription`)
    are public, only `doLoadUsersForDirectoryGroups()` is protected
  - Added impersonation guard detail to Built-in Roles: `RoleAdminController` write operations
    check `authUser` (not apparent user) for `HOIST_ROLE_MANAGER`, preventing impersonated users
    from modifying roles
  - Added "Soft-Config Gates" subsection to Common Patterns: documents `HoistUser.hasGate()` as
    a lighter-weight, config-backed access mechanism for gating features under development
- Marked Done, DRAFT banner removed
- Priority 1 (Core Framework) now fully complete: all 4 docs Done

### 2026-02-16 — Interactive review: preferences
- Reviewed `preferences.md` against all source files (Preference, UserPreference, PrefService,
  PrefDiffService, PreferenceAdminController, XhController, BootStrap)
- Key corrections and additions:
  - Removed outdated `local` flag references from preferences.md, README.md, and docs-roadmap.md
    (feature was removed from hoist-react)
  - Removed phantom `xhPreferenceChanged` event from roadmap description (does not exist in
    codebase, unlike `xhConfigChanged` for configs)
  - Added PreferenceAdminController and XhController to source files table
  - Documented untyped `setPreference()` method with guidance to prefer typed setters
  - Added Built-in Preferences table cataloging 6 `xh`-prefixed prefs from Hoist BootStrap
  - Strengthened `ensureRequiredPrefsCreated` guidance: apps should register all prefs they use;
    non-existent prefs throw RuntimeException
  - Added `note` vs `notes` API naming inconsistency callout
  - Clarified PrefDiffService and PreferenceAdminController as internal Hoist implementation
    services, not public APIs for application code
  - Removed `getLimitedClientConfig` documentation (internal framework method)
  - Added link to hoist-react persistence documentation — most client-side pref interaction
    happens through the persistence system's `persistWith` mechanism
  - Simplified cascade deletion pitfall wording
- Marked Done, DRAFT banner removed

### 2026-02-22 — New doc: build-and-publish
- Created `build-and-publish.md` documenting the Gradle build pipeline and Maven Central publishing
- Covers all three GitHub Actions workflows: CI (`gradle.yml`), snapshot publishing
  (`deploySnapshot.yml`), and release publishing (`deployRelease.yml`)
- Documents the full Gradle publishing configuration: `maven-publish` plugin, `signing` plugin,
  `nexus-publish-plugin`, the `hoistCore` MavenPublication, POM metadata, and artifact signing
  with in-memory PGP keys
- Covers version numbering (`xhReleaseVersion` property), required GitHub secrets, the Sonatype
  Central Portal staging/release flow, and the legacy `repo.xh.io` publishing path
- Documents `settings.gradle` (sets `rootProject.name` for correct artifact naming in CI)
- Includes a step-by-step release checklist
- Added new "Build & Publishing" section to docs-roadmap.md and docs/README.md index
- This doc sits outside the priority tiers (like gorm-domain-objects) — it covers build
  infrastructure rather than a Hoist feature area

### 2026-02-21 — Interactive review: logging
- Reviewed `logging.md` against all 13 referenced source files
- Bug fix discovered and committed: `LogReaderService.doRead()` forward-reading path ignored the
  `caseSensitive` parameter, always doing case-insensitive matching. Fixed to use compiled Pattern
  consistently in both tail and forward paths. Also guarded `Pattern.compile()` against null
  pattern input. Committed separately with CHANGELOG entry.
- Key corrections and additions:
  - Fixed `withTrace` started message description: clarified that the started message is always
    logged for `withTrace` (since TRACE is already the finest level), unlike `withInfo`/`withDebug`
    which require a finer level to be enabled
  - Added instance-aware log file naming detail: default appenders include the cluster instance
    name in filenames (e.g., `myapp-inst1-app.log`), ensuring each instance writes to its own files
  - Added `additivity: false` note for tracking/monitoring loggers, and documented the minimal
    `%m%n` track log layout (entries carry their own timestamps)
  - Fixed `Supported levels` order to match `LogLevel.LEVELS` constant (`Inherit` before `Off`)
  - Added local dev log path fallback: when `catalina.base` is not set, logs go to `[appCode]-logs`
    relative to the working directory
  - Added "Custom layouts for structured logging" subsection: documents Closure-based Layout support
    in `createEncoder()`, with a complete JSON logging example using `logback-json-classic` and
    `logback-jackson` dependencies
  - Changed example username from `homer` to `jdoe` throughout
- Marked Done, DRAFT banner removed

### 2026-02-26 — Application structure doc + metrics.md roadmap reconciliation
- Created `application-structure.md` — a structural guide to the standard Hoist application
  repository layout, covering both server-side (Grails) and client-side (React/TypeScript)
  conventions, build configuration, Docker deployment, and local development workflow
- Research based on cross-referencing four production Hoist applications (Toolbox, plus three
  private apps) to identify consistent structural patterns across all XH-built projects
- New "Application Development" section added to both `docs/README.md` and `docs-roadmap.md` to
  house this and future app-development guides (distinct from feature-area API docs)
- Added `metrics.md` to docs-roadmap.md Priority 3 section — file existed on disk and in README
  index but was missing from the roadmap (added as Done, no DRAFT banner present)

### 2026-03-10 — New doc: tracing
- Created `tracing.md` as part of the distributed tracing feature implementation (TracingService,
  TracingConfig, TracingAdminService, TracingAdminController)
- Follows `metrics.md` structure: Overview, Source Files, Service API, Configuration, Export
  Configuration, Built-in Instrumentation, Context Propagation, Log Correlation, Admin Console,
  Resource Attributes
- Added to docs/README.md (Quick Reference + Infrastructure & Operations table)
- Added to docs/doc-registry.json (infrastructure viewerCategory, package mcpCategory)
- Added to docs-roadmap.md Priority 3 table (marked Done, no DRAFT banner)

### 2026-04-15 — New doc: changelog-format

- Broke out `changelog-format.md` from the `xh-upgrade-notes` skill bundle into a top-level
  `docs/` document — mirrors the same pattern already in place in hoist-react
- Enhanced with "Library vs. Application changelogs" distinction and explicit Breaking Changes
  section requirements
- Added to README, doc-registry (`conventions` mcpCategory), and roadmap (Done)
- Doc index entries clarify this covers the hoist-core *library* CHANGELOG; applied the same
  clarification to hoist-react indexes for consistency

### 2026-04-25 — New doc: coding-conventions (DRAFT)

- Created `coding-conventions.md` as the authoritative coding conventions reference for hoist-core,
  consolidating guidance previously scattered across `CLAUDE.md`/`AGENTS.md`, individual feature
  docs, and tribal knowledge
- Modeled on the hoist-react `coding-conventions.md` doc (paired-sibling structure and tone) but
  with sections adapted to the Groovy/Grails/Hazelcast stack
- Sections: Overview, Principles, Naming, Logging and Exceptions, Services and Lifecycle,
  Controllers and Security, GORM and Data Access, Clustering and Caching, HTTP/Email/Background
  Work, Code Style, Commit Messages/PRs/Comments, Reference table
- Cross-references rather than duplicates the existing feature docs (base-classes, authorization,
  request-flow, configuration, preferences, clustering, gorm-domain-objects, json-handling, logging,
  exception-handling, http-client, email, websocket); each section ends with or includes a pointer
  to its deep doc
- Do/don't paired Groovy snippets throughout to mirror the example density of the hoist-react
  conventions doc
- New "Conventions" section added to `docs-roadmap.md` to house this doc; conventions docs sit
  outside the priority tiers (mirroring how Grails Platform is organized)
- New "Conventions" section also added to `docs/README.md` Feature Documentation, distinct from
  the existing Development & Builds section where `changelog-format.md` lives (changelog-format is
  release-process specific, not a general coding convention)
- Added to doc-registry as `conventions` mcpCategory, `overview` viewerCategory (the doc applies
  broadly across categories rather than to one feature area)
- DRAFT banner in place; awaiting human review before promotion to Done
- `CLAUDE.md` intentionally not modified — that file may be slimmed down later once the
  conventions doc is reviewed and approved as the canonical reference

### 2026-04-25 — coding-conventions promoted to Done

- Reviewed in session and accepted; DRAFT banner removed
- Refinements applied during review:
    - Logging section now opens with an explicit pointer to `logging.md` for the full reference
    - New "Pass Maps for Structured Key-Value Data" subsection documenting Hoist's structured
      logging convention (do/don't pair, `_`-prefix metadata keys, sensitive-value caveat,
      mixing maps with strings and trailing exceptions)
    - Time Blocks section gained a "Consider tracing for important operations" call-out
      pointing at `tracing.md` (`traceService.withSpan`, `BaseService.observe()` /
      `ObservedRun`) — `withInfo`/`withDebug` for incidental timing, tracing API the moment
      latency-by-tag inspection is needed
    - "Mandatory Access Annotations" section now ends with a pointer to `authorization.md`
    - `@ReadOnly` performance claim softened — "modestly faster for queries that load many
      entities, and a guardrail against accidentally persisting in-method mutations" replaces
      the unverified "significantly faster" framing (matches the more honest phrasing in
      `gorm-domain-objects.md`)
- `docs-roadmap.md` Conventions table row flipped Draft → Done; status overview line updated
  to match
- Two follow-up PR comments pending after commit + push:
    - PR #545 (typed soft-config) — flag the "Use configService Typed Getters" section as
      needing a typed-config addendum once #545 lands
    - PR #538 — flag the "Bootstrap Required Resources" section similarly

### 2026-05-28 — `build-and-publish.md` legacy-archive URL updated to `maven-archive.xh.io`

- Replaced the broken `repo.xh.io/content/groups/public/` Nexus-2-path snippet in the
  "Consuming the Artifact" section with a new "Legacy versions: `maven-archive.xh.io`"
  subsection. The old URL pre-dated the 2018 Nexus 2 → Nexus 3 migration and would have
  404'd for anyone copy-pasting it today; the new URL points at a static S3+CloudFront
  Maven archive that XH has provisioned as the long-term home for pre-Central
  `io.xh:hoist-core` releases (versions `0.1.0` through `36.2.0`)
- Doc clarifies scope explicitly: archive contains only `io.xh:hoist-core` release
  artifacts; no snapshots, no other `io.xh.*` libraries, no third-party content. Anything
  outside that scope returns 404
- Doc clarifies the boundary with Maven Central: current releases (`37.x` and later) are
  published exclusively to Central; the archive is for historical resolution only
- `docs/README.md` Key Topics line for `build-and-publish.md` updated: `repo.xh.io` →
  `maven-archive.xh.io (legacy 36.x-and-earlier hoist-core releases)`
- This roadmap's Key Topics line for `build-and-publish.md` similarly updated
- `build-and-publish.md` is still Draft (banner remains); this edit didn't change status
- Related stale references found via grep but **not** touched in this session, flagged for
  future review:
    - `docs/upgrade-notes/v34-upgrade-notes.md` (lines 218, 227) — Before/After blocks both
      cite the Nexus-2-path URL. The "Before" is appropriately historical; the "After" is
      arguably stale since the URL it endorses has not worked since the 2018 migration.
      Worth a small update during the next v34 review to point the "After" at
      `maven-archive.xh.io` if any new reader follows it today
    - `docs/planning/docs-roadmap-log.md` (this file, prior entries) — left intact per
      append-only convention; historical references to `repo.xh.io` are accurate to their
      time of writing

### 2026-07-29 — Cache/CachedValue `onChange` threading contract documented

Documented the change-handler threading contract for `Cache` and `CachedValue`, which was
previously absent from all docs and Groovydoc. Prompted by a question about whether `replicate:
true` affects how synchronously `onChange` handlers run — it does, and nothing said so.

- **Behavior documented** (verified against source, not inferred):
    - `Cache.put()` fires handlers *synchronously* on the calling thread only when
      `useCluster` is false (`Cache.groovy:157`). When clustered, `put()` does not fire handlers
      at all — a Hazelcast `ReplicatedMap` entry listener drives them asynchronously on a
      Hazelcast event thread, on every instance including the originating one
      (`CacheEntryListener.groovy`)
    - `CachedValue.setInternal()` always dispatches via a Grails `Promise` (`task {}`,
      `CachedValue.groovy:174`) regardless of `replicate` — so `Cache`-when-not-clustered is the
      only synchronous combination of the four
    - The switch is `replicate && ClusterService.multiInstanceEnabled`, not `replicate` alone.
      `multiInstanceEnabled` defaults to true (`ClusterConfig.groovy:60`), so a dev environment
      that sets it to `false` gets synchronous delivery where production gets asynchronous —
      called out explicitly as a dev/prod divergence hazard
- **`docs/base-classes.md`**: new `#### Change Handlers (onChange)` section under Resource
  Factories with a 3-row delivery matrix, the `multiInstanceEnabled` caveat, exception-propagation
  and once-per-instance consequences, a wrong/right code example, and an `oldValue` /
  `serializeOldValue` note. `onChange` rows in the `createCache()` param table and the
  `createCachedValue()` param paragraph now cross-reference it. New Common Pitfall: "Relying on
  synchronous `onChange` delivery"
- **`docs/clustering.md`**: short notes in the Cache and CachedValue sections (both linking to the
  base-classes section rather than restating it) plus a new Common Pitfall, "Assuming `replicate`
  doesn't change `onChange` timing"
- **Groovydoc**: expanded `Cache.onChange`, `Cache.addChangeHandler`, `CachedValue.onChange`,
  `CachedValue.addChangeHandler`
- **Stale reference fixed**: `CacheEntryChanged.getOldValue()` Groovydoc cited
  `optimizeRemoval = true`, a parameter that no longer exists — corrected to
  `serializeOldValue = false` (the default) and noted that the getter returns null and warns.
  Grep confirms no other `optimizeRemoval` references remain in the repo
- **Registry**: added `onChange`/`addChangeHandler` keywords to `docs/base-classes.md` and
  `replicate`/`onChange` to `docs/clustering.md` in `docs/doc-registry.json` for MCP
  discoverability. No entries added or removed — both docs already registered and `Done`
- Both docs remain `Done`; no status changes. `./gradlew assemble` passes
- Pre-existing item **not** touched: `docs/changelog-format.md` (lines 19, 96) contains
  `docs/upgrade-notes/v{NN}-upgrade-notes.md` links that a link checker flags as broken. These
  are intentional template placeholders showing the naming pattern, not real links
- Possible follow-up, not addressed here: `CacheEntryChanged.getOldValue()` logs a warning
  whenever `serializeOldValue` is false, including on the non-clustered path where `put()` did
  pass a real old value through and no serialization was involved. Behavior is consistent, but
  the warning reads as a misconfiguration complaint in a case where nothing was misconfigured.
  Worth revisiting whether the local path should surface `oldValue` without opt-in

### 2026-07-30 — New doc: caching (extracted from base-classes + clustering)

Created `docs/caching.md` as a dedicated home for the `cache/` and `cachedvalue/` packages. Prompted
by the observation that the `onChange` threading work (previous entry) had pushed cache material to
roughly a third of `base-classes.md` — a doc nominally about BaseService/BaseController/RestController.

- **Scope decided interactively:** the 7 classes across `io.xh.hoist.cache` and
  `io.xh.hoist.cachedvalue`. `IMap`, `ReplicatedMap`, `ISet`, and the Hibernate second-level cache
  stay in `clustering.md` / `gorm-domain-objects.md` — this is not a general "caching in Hoist" doc
- **Extraction depth:** `base-classes.md` keeps brief `createCache()` / `createCachedValue()`
  subsections (minimal `init()` example, parameter names, pointer); all tables, semantics, patterns,
  and pitfalls moved. `clustering.md` keeps only the Hazelcast mapping (backing structure per mode,
  resource-name patterns, the `multiInstanceEnabled` caveat, `ensureAvailable` example)
- **Relocated:** `createCache()`/`createCachedValue()` param + API tables, the whole
  `Change Handlers (onChange)` section, `The getOrCreate Pattern`, `Timer-driven Cache Refresh`,
  and 3 pitfalls (sync `onChange` reliance, `replicate` changing timing, large objects in replicated
  caches). Left in place deliberately: `clearCachesConfigs` and the `super.clearCaches()` pitfall
  (BaseService lifecycle, not cache mechanics), plus the non-serializable-values and
  immediate-replication pitfalls (general to all Hazelcast structures)
- **New material** (deliberately scoped to "light additions" per reviewer decision — Kryo
  `serializeValue` mechanics, `CachedValueEntry` uuid dedup, and `ReliableTopic`
  `retrieveInitialSequence` replay internals were left undocumented):
    - `Choosing a Structure` — Cache vs CachedValue vs IMap vs ISet selection table
    - `Differences from Cache` — added on review, after the reviewer flagged that describing
      `CachedValue` as having "the same expiry and replication options" as `Cache` reads as a
      guarantee that does not hold. Source comparison found six real divergences: no
      `serializeOldValue` (so `oldValue` is always available), a null value that never expires
      because `shouldExpire` short-circuits on it, `expireTime: 0` silently ignored because the
      check is a Groovy truth test rather than a null test, no cull timer, a different replication
      transport, and always-async `onChange`. The age comparison itself *is* equivalent
      (`intervalElapsed` reduces to `now > timestamp + ttl`, matching `Cache`'s inline check), so
      that one sameness claim is stated explicitly and kept
    - `Expiration and Culling` — `expireTime` as closure, `expireFn` precedence over `expireTime`,
      `timestampFn` semantics, lazy-on-access vs. the 15-minute `primaryOnly` cull timer
    - `Waiting for Values: ensureAvailable()` — parameter table, `TimeoutException`, interaction with
      the bounded `init()` window
    - `Clearing and Invalidation` — why `clear()` removes key-wise (per-entry events + avoids a
      Hazelcast `ReplicatedMap.clear()` error)
    - `Admin Console` — `adminStats` fields per class, `comparableAdminStats` returning empty for
      non-replicated caches, per-cache logger namespaces. Expanded on review after the reviewer read
      the first draft as implying reporting was automatic and suspected services must surface cache
      stats from their own `getAdminStats()`. Traced the code: reporting *is* automatic, but the
      draft never said how, which is what made it ambiguous. Both `ServiceManagerService`
      (Cluster > Services) and `ClusterObjectsService` (Cluster > Objects) walk `BaseService.resources`
      and pick up anything implementing `AdminStats`; `createCache()` / `createCachedValue()` register
      there via `addResource()`. Confirmed empirically that `DefaultRoleService` and
      `AlertBannerService` add only *derived* data (counts, the current banner) to their own
      `getAdminStats()`, never the cache's own stats. Section now documents the registration chain,
      notes that `xh_`-prefixed resources are filtered from the Services tab, names the actual
      `comparableAdminStats` keys, and warns that a directly-constructed cache is invisible to both
      tabs — a concrete reason the factory methods are the only supported construction path
    - Pitfalls: **caching null** (`put(key, null)` removes; a `getOrCreate` closure returning null
      re-runs every call), **inexact `size()`** (counts expired-but-uncculled entries), and
      **`asBoolean()` truthiness** (`if (cache)` tests non-empty, not non-null)
- **Indexes:** `docs/README.md` row in Core Features + 2 Quick Reference entries;
  `doc-registry.json` entry (`package` / `core-features`, 16 keywords); roadmap entry in Priority 2;
  Status Overview updated 5 → 6 Core Features docs
- Removed `onChange`/`addChangeHandler` from `base-classes.md`'s registry keywords (added in the
  previous session) since that content now lives in `caching.md`; added `createISet` in their place.
  Left `onChange` on `clustering.md`, which still discusses replication's effect on handler timing
- **Shipped without a DRAFT banner and marked Done** at the reviewer's explicit direction, on the
  basis that nothing is committed until they have read and approved it. Note this departs from the
  usual Planned → Draft → Done convention; most of the content is relocated text that was already
  reviewed as part of `base-classes.md` and `clustering.md`
- `base-classes.md` 731 → 527 lines, stays Done (remaining content is unchanged reviewed text plus
  pointers). `clustering.md` 443 → 399. New `caching.md` is 470 lines
- Source-verified while writing, which surfaced four Groovydoc inaccuracies in the two packages.
  All fixed in this session (comment-only; no behavior changed):
    - `Cache.size()` read "may include *unexpired* entries that have not yet been culled" — it means
      *expired*. Now states that it over-reports until culling and points at `getMap()`
    - `expireTime` was described as "epochMillis Long" in **both** `Cache` and `CachedValue`. It is a
      *duration* in ms — the implementation adds it to the entry timestamp
      (`currentTimeMillis() > timestamp + expire`), so an epoch value would be nonsensical. Now
      documented as a duration measured from the entry timestamp
    - `CacheEntryChanged.key` carried a note that "when source of this change is a CachedValue, this
      key will simply be the name of the CachedValue." Stale — `CachedValue` fires
      `CachedValueChanged`, an unrelated class with no `key` and no inheritance link, and
      `CacheEntryChanged.source` is typed `Cache`. The situation the note describes cannot occur
    - `CachedValue.ensureAvailable()` said "wait for the *replicated* value" though it behaves
      identically when not replicated
- One underlying oddity found and deliberately **not** changed, flagged for a future decision:
  `CachedValue.getTimestamp()` returns **0**, not null, before the value is ever set — the
  uninitialized `CachedValueEntry` hard-codes `dateEntered = 0L` because Kryo serializes it as a
  primitive `long`. Its Groovydoc previously claimed "or null if none". The comment now documents
  the real behavior; whether the *behavior* should instead return null is a separate call, since it
  also feeds `adminStats.timestamp`

### 2026-07-30 (cont.) — Doc-consistency pass: missing v37 README row

Ran the doc-links consistency check over all 33 docs after the `caching.md` extraction. The caching
work itself reconciled clean (README row, registry entry, roadmap entry all present and accurate;
0 broken links, 0 broken anchors). Two unrelated items surfaced:

- **Fixed:** `docs/README.md`'s Upgrade Notes table was missing its `v37.0.0` row entirely. Every
  other version from v34 through v40 was listed. The doc, its registry entry, and its roadmap entry
  all existed, so only the README table was affected. Row added between v38 and v36 summarizing
  OpenTelemetry tracing, the MCP server, `xhMetricsPublished`, and the `MetricsService` namespace
  removal
- **Not a defect:** `docs/changelog-format.md` (lines 19, 96) links to
  `docs/upgrade-notes/v{NN}-upgrade-notes.md`, which any link checker reports as broken. These are
  intentional template placeholders showing the filename pattern. Left as-is, and noted here so
  future runs stop re-investigating them

### 2026-08-13 — Simplified Technical English adopted for CHANGELOG entries

XH is moving written docs and changelogs to the [ASD-STE100](https://asd-ste100.org) Simplified
Technical English standard. First application: `docs/changelog-format.md` gained a
`## Simplified Technical English` section, and the doc's own prose was rewritten to conform.

- **New guidance:** a 9-row Core Rules table (sentence length, active voice with a named subject,
  no participial clauses, simple tenses, one word one meaning, noun-cluster limit, `because` for
  cause, full stop over semicolon, no slash as a conjunction), plus a Modals table that retires
  `should` in favor of stating the consequence. Articles and contractions are covered in prose
  rather than as table rows - the rule names are self-executing and the examples were straw men
- **Every table row is a parallel pair** - both cells carry the same content so the lesson is the
  diff between them. An early draft had rows whose two cells described different things, which
  teaches nothing. Worth preserving as a review check on any future rule added here
- **Interaction with existing conventions is explicit:** STE governs sentence construction and does
  not displace the verb-first / symbol-first openers, the explicit-subject rule, or the ASCII
  punctuation rule. Where the two conflict, the section-specific rule wins
- **Also tightened:** the Libraries section now requires both sides of the arrow to be abbreviated
  to the same depth. The examples had been showing `6.2.3 → 7.0`, violating the rule they
  illustrated. The 40.2.0 entry's `Grails 7.1.1 → 7.2.0` was normalized to `7.1 → 7.2`
- The `41.0-SNAPSHOT` CHANGELOG entry was rewritten to the new standard and serves as the worked
  reference for it
