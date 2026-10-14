# Deployment Inventory

> **Status: DRAFT** - This document is awaiting review by an XH developer. Content may be
> incomplete or inaccurate.

## Overview

Organizations running many Hoist applications - often inside locked-down enterprise networks -
need a reliable way to answer questions like:

- Which apps and environments are deployed, and at what app, Hoist, and library versions?
- Which deployments still carry a vulnerable version of a given library?
- What Java, OS, servlet container, and database versions do they run on?
- Which Hoist features (LDAP, Entra ID, WebSockets, tracing, metrics) are in use where?
- Is each app actually used, and are users still running stale client builds?
- Are any monitors currently in WARN or FAIL?

The **inventory report** answers these from inside each deployment. `EnvironmentService` builds a
single JSON document describing the deployment, served from a whitelisted (pre-auth) endpoint,
`/xh/inventory`, so a central job can sweep every app over the internal network without an
interactive login. The report is **opt-in**: it is disabled by default and token-protected once
enabled.

### Source Files

| File | Location | Role |
|------|----------|------|
| `EnvironmentService` | `grails-app/services/io/xh/hoist/environment/` | Builds and caches the report, validates access |
| `InventoryConfig` | `src/main/groovy/io/xh/hoist/environment/` | Typed `xhInventoryConfig` soft config |
| `InventoryContributor` | `src/main/groovy/io/xh/hoist/environment/` | Interface for app services to add custom data |
| `LibraryInventory` | `src/main/groovy/io/xh/hoist/environment/` | Classpath JAR scan and framework version lookup |
| `TrackLogAdminService` | `grails-app/services/io/xh/hoist/track/` | `getUsageSummary()` - aggregate activity counts |
| `XhController` | `grails-app/controllers/io/xh/hoist/impl/` | `inventory` action at `/xh/inventory` |
| `BaseAuthenticationService` | `src/main/groovy/io/xh/hoist/security/` | Whitelists `/xh/inventory` by default |

## Enabling the Report

Two soft configs, both created at bootstrap:

| Config | Type | Default | Purpose |
|--------|------|---------|---------|
| `xhInventoryConfig` | `json` | `{enabled: false, ...}` | Master switch and options - see below |
| `xhInventoryToken` | `pwd` | `none` | Shared secret callers must present |

To enable, set `xhInventoryConfig.enabled` to `true` and give `xhInventoryToken` a long random
value. Both can be set from the Admin Console, or supplied per deployment as instance config,
which takes precedence over the database value. For example, as environment variables:

```bash
APP_PORTFOLIO_XH_INVENTORY_CONFIG='{"enabled": true, "tags": {"team": "Risk Tech"}}'
APP_PORTFOLIO_XH_INVENTORY_TOKEN='<long random secret>'
```

Instance config is the better fit for a fleet: operations teams can enable the report and rotate
the token through their deployment tooling, without touching each app's database.

### `xhInventoryConfig` Options

| Key | Default | Description |
|-----|---------|-------------|
| `enabled` | `false` | Serve the report. When false, `/xh/inventory` returns 404. |
| `requireToken` | `true` | Require callers to present `xhInventoryToken`. Set `false` only where network controls already restrict the endpoint. |
| `excludeSections` | `[]` | Section names to omit - see [Report Sections](#report-sections). |
| `usageDays` | `30` | Trailing window for the `usage` section. |
| `includeDatabaseUrl` | `false` | Include the JDBC URL in `database`. Off by default, as some drivers accept credentials in the URL. |
| `cacheMins` | `10` | Minutes to cache the report on each instance. |
| `tags` | `{}` | Free-form metadata included verbatim - e.g. owning team, business unit, support contact, asset ID. |

## Access Model

`EnvironmentService.ensureInventoryAccess()` runs before the report is built, in this order:

1. **Disabled** - throws `NotFoundException` (404), so a disabled endpoint looks like a missing
   one.
2. **Admin readers** - an authenticated user with `HOIST_ADMIN_READER` is always allowed, with no
   token. Useful for checking the report from a browser session.
3. **No token required** - allowed if `requireToken` is `false`.
4. **Token** - the caller must present `xhInventoryToken` via either header:
   - `X-XH-Inventory-Token: <token>` (preferred)
   - `Authorization: Bearer <token>`

   A missing, wrong, or unconfigured token throws `NotAuthenticatedException` (401) and logs a
   WARN with the caller's remote address. Tokens are compared by SHA-256 digest in constant time.

The custom header is preferred because some SSO setups - for example NTLM/Kerberos negotiation via
Jespa, or an authenticating reverse proxy - intercept or rewrite the `Authorization` header.

```bash
curl -s -H "X-XH-Inventory-Token: $TOKEN" https://portfolio.internal.example.com/xh/inventory
```

The endpoint is whitelisted in `BaseAuthenticationService.whitelistURIs`, so app
`AuthenticationService` implementations pick it up automatically. Apps that replace (rather than
append to) `whitelistURIs` must add `/xh/inventory` themselves. Like all requests, it returns 503
until the instance has finished starting.

## Report Sections

Every report starts with metadata, then includes each section not listed in `excludeSections`:

| Key | Description |
|-----|-------------|
| `inventoryVersion` | Report format version (currently `1`) - incremented on incompatible changes. |
| `generated` / `generatedBy` | When, and on which instance, the report was built. |
| `tags` | `xhInventoryConfig.tags`, verbatim. |

| Section | Contents |
|---------|----------|
| `app` | `appCode`, `appName`, `appPackage`, `appVersion`, `appBuild`, `appEnvironment`, `isProduction`, `hoistCoreVersion`, `hoistReactVersion`. |
| `runtime` | Java version, vendor, and VM; OS name, version, and arch; servlet container (e.g. `Apache Tomcat/11.0.x`); `kubernetes` flag; server and app time zones. Read from the instance that built the report. |
| `dependencies` | Versions of key frameworks: `java`, `groovy`, `grails`, `hoistCore`, `hoistReact`, `springBoot`, `spring`, `hibernate`, `hazelcast`, `jackson`, `tomcat`, `micrometer`, `openTelemetry`, `logback`. Flat strings, for easy tabulation. |
| `cluster` | `clusterName`, `multiInstanceEnabled`, `primaryName`, `instanceCount`, and an `instances` list gathered from every member: name, primary flag, state, startup time, uptime, host name, PID, processors, heap used and max, WebSocket channel count. |
| `database` | Product name and version, JDBC driver name and version, `dbCreate`, and optionally `url`. |
| `features` | Class names of the app's `AuthenticationService`, `UserService`, and `RoleService`, plus whether LDAP, Entra ID, impersonation, activity tracking, monitoring, WebSockets, tracing, Prometheus and OTLP metrics, alert banners, and `xhEmailOverride` are active. |
| `monitors` | Whether monitoring is enabled, the worst current status, counts by status, and each WARN or FAIL monitor with its message and last status change. |
| `usage` | Activity tracking over `usageDays`: entries, distinct users (window, 7 days, 1 day), app loads, client errors, feedback, last activity, and breakdowns by client app, client version, browser, and device. **Counts only** - no usernames. |
| `libraries` | Every JAR on the classpath: `name`, `version`, `module` (`Automatic-Module-Name` or `Bundle-SymbolicName`), and raw `file` name. The basis for CVE sweeps. |
| `custom` | Data from app services implementing `InventoryContributor`, keyed by bean name. |

Each section is built independently. If one fails - for example the database is unreachable -
it reports `{error: "<message>"}` in place of its content and the rest of the report is still
returned. The failure is logged at ERROR on the server.

### How library versions are resolved

`LibraryInventory` enumerates every `META-INF/MANIFEST.MF` visible to Hoist's classloader, which
covers `BOOT-INF/lib` (Spring Boot JAR), `WEB-INF/lib` (WAR in external Tomcat), and resolved
dependency JARs in local development. For each JAR:

- If the manifest's `Implementation-Version` (or `Bundle-Version`) matches the end of the file
  name, the name and version are split there - e.g. `spock-core-2.4-M4-groovy-4.0.jar`.
- Otherwise the version starts at the last dash-separated token that begins with a digit - e.g.
  `log4j-1.2-api-2.24.3.jar` gives `log4j-1.2-api` / `2.24.3`. Gradle and Maven name resolved JARs
  `{artifactId}-{version}.jar`, so the file name is the most reliable record of what was resolved.
- Unversioned file names (e.g. `ojdbc11.jar`) fall back to the manifest version, if any.

The scan runs once per JVM, on first request, and is held for the life of the instance.

## Adding App-Specific Data

Implement `InventoryContributor` on any service to add data to the `custom` section - typically
versions of upstream systems the app depends on, or app-level facts a central inventory needs:

```groovy
class MarketDataService extends BaseService implements InventoryContributor {

    Map getInventoryData() {
        [
            feedProvider : 'Bloomberg B-PIPE',
            clientVersion: bpipeClient.version,
            subscriptions: subscriptionCount
        ]
    }
}
```

Contributions are collected via `getBeansOfType(InventoryContributor)` and wrapped like the
built-in sections, so an exception reports an `error` for that contributor only. Return only
JSON-serializable data that is appropriate for whoever runs the sweep.

## Using the Report Programmatically

`environmentService.getInventory()` returns the (cached) report without any access check, so apps
can deliver it by other means - for example, a `primaryOnly` timer that POSTs it to a central
collector from networks where inbound sweeps are not allowed:

```groovy
class InventoryPushService extends BaseService {

    def configService,
        environmentService
    private JSONClient client = new JSONClient()

    void init() {
        createTimer(
            name: 'pushInventory',
            runFn: this.&pushInventory,
            interval: 1 * DAYS,
            primaryOnly: true
        )
    }

    private void pushInventory() {
        def post = new HttpPost(configService.getString('inventoryCollectorUrl'))
        post.entity = new StringEntity(JSONSerializer.serialize(environmentService.inventory), APPLICATION_JSON)
        client.executeAsStatusCode(post)
    }
}
```

## Common Pitfalls

### Enabling with `requireToken: false` on a reachable network

The report reveals versions, host names, and library lists - exactly what an attacker wants for
target selection. Keep the token unless the endpoint is only reachable from the sweep host, and
treat the token as a credential: store it in instance config or a secrets manager, not in source.

### Replacing `whitelistURIs` instead of appending

An app `AuthenticationService` that assigns a new list to `whitelistURIs` drops `/xh/inventory`
along with the other Hoist entries. Append with `whitelistURIs.push(...)` instead. If the endpoint
returns a login redirect or 401 for a valid token, check this first.

### Polling more often than `cacheMins`

The `usage` section runs several aggregate queries over the track log, and `cluster` fans out to
every instance. These are cached for `cacheMins` on each instance, but a sweep behind a load
balancer may hit a different instance each time. Daily or hourly sweeps are the intended use.

### Expecting per-user detail

`usage` deliberately reports counts only. Use the Admin Console's Activity tab, or the track log
directly, for user-level analysis.
