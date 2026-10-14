---
status: proposal
---

# Proposal: Cross-App Deployment Inventory

Design notes, rationale, and next steps for the deployment inventory report. The feature itself
is documented in [`../inventory.md`](../inventory.md), which is authoritative for behavior.

## Problem

XH and client teams run many Hoist apps across environments, mostly inside client networks we
cannot reach from outside. Today, answering "which apps run Hoist < X", "who still ships
jackson-databind 2.x", or "is this app even used" means logging into each Admin Console, or
asking each team. There is no machine-readable, uniform source.

## Goals

- One JSON document per deployment that a central job can collect and tabulate.
- Zero app code required: enable via soft config or instance config.
- Opt-in and safe by default: disabled until enabled, token-protected once enabled.
- Reachable without an interactive login, since sweeps run as a service.
- Cheap and robust: cached, and one failing section never breaks the report.

## Non-Goals

- Per-user data. `usage` is counts only.
- A collector, dashboard, or CVE database. The report is the input those would consume.
- Replacing metrics or monitoring. The report is a periodic snapshot, not a time series.

## Design Decisions

### Build on `EnvironmentService`, served from `XhController`

`EnvironmentService` already owns "metadata describing the runtime environment", and the
`/xh/environment` payload already carries a subset of this data. The inventory is a superset for a
different audience, so it lives beside it. `/xh/inventory` follows the existing `/xh/ping` and
`/xh/version` pattern for whitelisted endpoints.

### Whitelisted, plus a shared token

Sweeps run as a service account or cron job with no SSO session, so the endpoint must be
pre-auth. Gating options considered:

| Option | Verdict |
|--------|---------|
| Shared token in a `pwd` config | **Chosen.** Encrypted at rest, rotatable, and overridable per deployment via instance config. |
| IP allowlist | **Rejected.** Nearly every deployment sits behind a load balancer or ingress, so `remoteAddr` is the proxy's address. Trusting `X-Forwarded-For` is spoofable unless the proxy chain is known. Network policy belongs in the network. |
| Require a real authenticated user | **Partial.** Allowed for `HOIST_ADMIN_READER`, useful for spot checks, but not viable for unattended sweeps across SSO schemes. |
| mTLS | Out of scope for the app tier. Can be layered at the ingress. |

The token is accepted via `X-XH-Inventory-Token` (preferred) or `Authorization: Bearer`. The
custom header exists because Jespa/NTLM setups and authenticating proxies may consume
`Authorization`. Query-string tokens are deliberately not supported, as they land in access logs.

A disabled report returns 404, not 403, so the endpoint does not advertise itself.

### Typed config with section exclusion

`xhInventoryConfig` is a `TypedConfigMap`, matching current Hoist practice. Rather than a boolean
per section, `excludeSections` takes a list: it is shorter, scales to new sections, and keeps the
default "everything". The one sensitive detail inside a section, the JDBC URL, has its own
default-off flag (`includeDatabaseUrl`) because some drivers embed credentials in it.

`tags` lets operations teams annotate a deployment (team, business unit, asset ID) without a code
change. This is likely the field central reporting joins on.

### Library versions from JAR file names, not `pom.properties`

Enumerating `META-INF/maven/**/pom.properties` would give exact Maven coordinates, but Gradle-built
JARs (including Hoist's own) do not contain it, and wildcard resource scans are fragile across
classloaders. Every packaging mode (Boot JAR, WAR in external Tomcat, local dev) does expose one
manifest per JAR, and the JAR file name is the resolved `{artifactId}-{version}`. The manifest's
`Automatic-Module-Name` / `Bundle-SymbolicName` is included as `module`, which usually reads as
`groupId.artifactId` and is enough to map to CVE feeds.

Verified against Toolbox via `bootRun` (216 JARs) and an executable WAR using Spring Boot's
`jar:nested:` URLs (215 JARs, all versioned). The WAR run showed `jackson-databind` 2.22.2 and
3.1.6 side by side - exactly the kind of finding a sweep is for.

### Cluster fan-out for per-instance data

Version is uniform across a cluster (the cluster name includes it), but heap, uptime, host, and
WebSocket counts are not. `cluster.instances` gathers them from every member via
`runOnAllInstances`. Failures for one instance report an `error` entry.

### Usage from the track log, counts only

`TrackLogAdminService.getUsageSummary()` runs a handful of indexed aggregate queries over the
`usageDays` window. The `byClientVersion` breakdown answers "are users stuck on an old client
build". The queries use criteria with `groupProperty`, which the `DataTest` in-memory datastore
does not support, so the spec runs on `HibernateSpec` over H2 (now a test-only dependency).

### Extension via `InventoryContributor`

Apps know things Hoist cannot: upstream system versions, entitlement counts, feature flags. A
one-method interface on any service is the lightest hook that needs no registration and is
discoverable via `getBeansOfType`.

## Security Review

| Risk | Mitigation |
|------|------------|
| Report aids attacker reconnaissance | Disabled by default; token required by default; 404 when disabled; WARN log with remote address on every rejected call. |
| Token brute force | Long random tokens; constant-time digest compare; rejections logged. Rate limiting is left to the ingress. |
| Token leakage | `pwd` config type (encrypted at rest); header only, never query string; instance config lets the secret live in a secrets manager. |
| Credential exposure via JDBC URL | URL excluded by default. Username and password are never included. |
| JVM args or env leaking secrets | Not included. Only selected system properties and `HOSTNAME` are read. |
| PII | `usage` is counts only. Monitor messages are included and are admin-authored text. |
| Load from aggressive polling | Per-instance cache (`cacheMins`, default 10). |

## Rollout Plan

1. Release in hoist-core 44.
2. Pick a sweep host per client network. Generate one token per environment, or per app.
3. Enable via instance config in each deployment's tooling:
   `APP_{APPCODE}_XH_INVENTORY_CONFIG` and `APP_{APPCODE}_XH_INVENTORY_TOKEN`.
4. Run a simple sweeper (below) on a schedule, writing results to wherever the client's reporting
   lives.

## Follow-Ups

Roughly in priority order:

1. **Sweeper script.** A small CLI (Groovy or Python) that reads a list of base URLs and tokens,
   collects reports, and writes JSON lines plus a flattened CSV (`appCode`, `appEnvironment`,
   versions, `tags`). This is the minimum to make the feature useful on day one. It could ship
   with `hoist-core-mcp` or as a standalone script in this repo.
2. **Admin Console view** (hoist-react). A read-only "Inventory" tab rendering the report, which
   `HOIST_ADMIN_READER` can already fetch without a token.
3. **SBOM output.** Optionally emit the `libraries` section as CycloneDX JSON
   (`/xh/inventory?format=cyclonedx`), so existing scanners (Dependency-Track, Grype) can consume it
   directly. The current name, version, and module data is close to sufficient.
4. **Client library versions.** The server knows `hoistReactVersion` from build metadata but not
   the client's npm dependencies. A build step could embed a client dependency manifest the server
   reports alongside the JARs.
5. **Push mode.** For networks that allow outbound but not inbound traffic, a built-in, opt-in
   `primaryOnly` timer that POSTs the report to a configured collector URL. The doc shows the
   app-level pattern; promote it to Hoist if demand appears.
6. **Version-lag scoring.** A collector-side computation of how far each app trails the latest
   Hoist and key-library releases.

## Open Questions

- Should `xhInventoryConfig` default to enabled-with-token-required in non-production
  environments, to reduce setup friction? Current answer: no - explicit opt-in everywhere is
  simpler to reason about and to audit.
- Should monitor `message` text be excluded by default? It is admin-authored, but some monitors
  interpolate data values.
- Is `features.authenticationService` (an app class name) useful enough, or should it report the
  nearest Hoist ancestor class instead?

## Issue Found During Testing (Unrelated)

Running Toolbox's executable WAR with `java -jar` fails at startup in
`InstanceConfigUtils.toSnakeCase()` when any `APP_*` environment variable is set:
`Utils.appCode` is still null while `application.groovy` loads, and Jackson's `translate(null)`
returns null, which then NPEs. Not yet checked for WARs deployed into an external Tomcat, which is
XH's standard deployment. Passing `-Dinfo.xh.appCode=...` works around it. Worth a separate fix: at minimum, guard the null and
fail with a clear message.
