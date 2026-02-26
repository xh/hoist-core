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
