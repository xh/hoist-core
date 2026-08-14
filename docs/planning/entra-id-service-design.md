---
status: active
date: 2026-08-13
topic: EntraIdService - Microsoft Entra ID directory queries via Microsoft Graph
---

# EntraIdService Design

New `EntraIdService` for querying a Microsoft Entra ID tenant for users, groups, and group
memberships via the Microsoft Graph API. An opt-in complement to `LdapService`, with first-class
support for powering `DefaultRoleService` directory-group role memberships.

## Motivation

Enterprise deployments are migrating corporate directories from on-prem LDAP/AD to Entra ID.
Key drivers, observed at a large multi-app deployment:

- New groups can be created "cloud only" in Entra ID and never replicate to on-prem LDAP
  domain controllers, making them invisible to `LdapService`.
- User identity is increasingly sourced from Entra-issued JWTs (MSAL client flows). Attribute
  changes (e.g. renames) can fail to propagate to on-prem LDAP replicas, breaking the join
  between JWT-sourced identity and LDAP-sourced group memberships.
- General mandate to retire on-prem LDAP querying.

A secondary goal: Graph user queries give apps a well-defined user object (department, contact
info, etc.) where the JWT alone provides only basic claims.

## Decisions

| Decision | Choice | Notes |
|---|---|---|
| Service name | `EntraIdService` | Matches MS product name "Entra ID" and existing Toolbox `EntraIdTokenService` precedent |
| Group identifier | Entra object ID (GUID) | Unambiguous, stable through renames. Admin UI must relay display names so users never see bare GUIDs (see Admin UI section) |
| User -> username join | Configurable `usernameAttribute` | Default `userPrincipalName`, lowercased. Alternatives: `mail`, `onPremisesSamAccountName` |
| Graph access | msal4j (auth) + Hoist `JSONClient` (REST) | Full Graph SDK rejected - heavy dependency tree for four endpoints. msal4j 1.25.1 runtime deps are only `slf4j-api` + `azure-json` |
| Credentials | Client secret (v1) | msal4j supports certificates (`ClientCredentialFactory.createFromCertificate`) - add if/when a deployment requires it |
| Role integration | New `DirectoryService` interface | Implemented by both `LdapService` and `EntraIdService`. `DefaultRoleService` selects an enabled provider |
| Provider selection | `xhRoleModuleConfig.directoryGroupProvider` | `ldap` / `entraId` / default `auto` (single enabled provider wins). Both enabled under auto = ERROR log + LDAP (incumbent) keeps working |
| v1 scope | Groups + single-user lookup | No bulk user enumeration in v1. `LdapService` untouched in its public API - no breaking changes |

## Components

| Component | Location | Role |
|---|---|---|
| `EntraIdService` | `grails-app/services/io/xh/hoist/entra/` | Graph queries, token management, caching, retry |
| `EntraIdConfig` | `src/main/groovy/io/xh/hoist/entra/` | `TypedConfigMap` for `xhEntraIdConfig` |
| `EntraUser` / `EntraGroup` | `src/main/groovy/io/xh/hoist/entra/` | Typed results, `JSONFormat` |
| `DirectoryService` | `src/main/groovy/io/xh/hoist/directory/` | Narrow contract consumed by `DefaultRoleService` |

`DirectoryService` models only what role resolution and its admin UI need:

- `getEnabled()`
- `getDirectoryGroupsDescription()` - admin UI hint ("enter a DN" vs "enter an object ID")
- `loadUsersForDirectoryGroups(Set groups, boolean strictMode)` - the exact pre-existing
  `DefaultRoleService.doLoadUsersForDirectoryGroups` contract (per group: `Set` of usernames or
  `String` error)
- `describeDirectoryGroups(Set groups)` - display info for assigned groups (admin UI relay)
- `searchDirectoryGroups(String namePart)` - name search (admin UI assist)

Richer query APIs (`lookupUser`, `lookupGroups`, `lookupGroupMembers`, `findGroups`) remain on
the concrete services with provider-specific types (`LdapPerson` vs `EntraUser`).

## Configuration

- `xhEntraIdConfig` (json, `typedClass: EntraIdConfig`):
  - `enabled` (false) - master switch; query methods throw when false
  - `tenantId`, `clientId` - Entra tenant and app registration
  - `usernameAttribute` (`userPrincipalName`) - Graph user field mapped to Hoist username
  - `stripUsernameDomain` (false) - true to strip the domain, e.g. `jdoe@example.com` -> `jdoe`.
    Covers apps whose Hoist usernames are the local part of the UPN/email. Works for cloud-only
    accounts, unlike mapping via `onPremisesSamAccountName`
  - `timeoutMs` (30000) - per Graph request
  - `cacheExpireSecs` (300, -1 disables) - same semantics as `xhLdapConfig`
- `xhEntraIdClientSecret` (pwd) - parallel to `xhLdapPassword`

Both created by Hoist BootStrap with `defaultValue: [:]` / `'none'`.

## Graph surface (v1)

All calls are GETs under `https://graph.microsoft.com/v1.0`, `$select`-trimmed, bearer-authed
via msal4j client-credentials tokens (scope `https://graph.microsoft.com/.default`; msal4j
caches and renews tokens internally - one shared `ConfidentialClientApplication` per service).

| Call | Endpoint | Notes |
|---|---|---|
| Group by ID | `/groups/{id}` | 404 -> "not found" as data, not an exception |
| Members (nested) | `/groups/{id}/transitiveMembers/microsoft.graph.user` | Nesting resolved server-side by Graph. The OData cast is an "advanced query": requires `ConsistencyLevel: eventual` header + `$count=true`. Paged via `@odata.nextLink`, `$top=999` |
| User | `/users/{id-or-upn}` | `department`, `onPremisesSamAccountName`, `accountEnabled` are NOT in Graph's default property set - explicit `$select` is mandatory |
| Group search | `/groups?$filter=startswith(displayName,'x')` | Works without advanced-query headers |

**Permissions**: application (app-only) permissions `GroupMember.Read.All` +
`User.Read.All`, granted with admin consent. Broader `Group.Read.All` / `Directory.Read.All`
work but are unnecessary. Hidden-membership groups would additionally need `Member.Read.Hidden`.

**Licensing**: confirmed these reads work on the free Entra ID tier - no P1/P2 license and no
paid Azure subscription features required. Avoid relying on license-gated features (dynamic
groups, sign-in log APIs, Identity Protection).

## Reliability model

Role-lockout protection is layered:

1. `DefaultRoleService.generateRoleAssignments` (pre-existing): on any directory lookup error,
   keeps the last successful lookup result and logs an error. Per-group error strings surface in
   the admin UI.
2. `strictMode` parity with LDAP: strict (admin preview) throws; non-strict (role refresh) logs
   and returns per-group error strings, engaging layer 1.
3. Bounded retry with linear backoff on Graph 429 and 5xx responses (2 retries). Follow-up:
   honor the `Retry-After` header value - requires surfacing response headers through
   `JSONClient` errors.
4. Group IDs are validated as GUIDs before any Graph call - malformed stored identifiers fail
   fast with a clear per-group message.
5. Token acquisition is warmed at startup (failure logged, does not block startup) so credential
   problems surface immediately in logs, distinct from query failures.
6. Results cached per query (`cacheExpireSecs`), local per instance, mirroring `LdapService`.
   Cluster replication remains at the role-assignment level (`DefaultRoleService`).

## Admin UI companion (hoist-react follow-up)

Because stored Entra group IDs are opaque GUIDs, the Roles admin UI needs:

- Display-name relay for assigned groups: new `roleAdmin/directoryGroupsInfo?names=...`
  endpoint -> `describeDirectoryGroups`.
- Name search when adding groups: new `roleAdmin/searchDirectoryGroups?query=...` endpoint ->
  `searchDirectoryGroups`.

Server endpoints ship now; the hoist-react Roles UI work (show displayName chips, add-group
search box) is a separate follow-up effort. Both endpoints also work for LDAP (id = DN,
displayName = CN), an improvement over today's truncated-DN display.

## POC / testbed plan (Toolbox + XH tenant)

Toolbox `RoleService` now delegates to `super.doLoadUsersForDirectoryGroups` when a real
directory service is enabled, else uses its existing mock.

XH-tenant setup (one-time, needs Azure admin auth):

```bash
# One shared directory-reader registration - do NOT reuse SPA sign-in registrations.
# App-only Graph directory reads are app-agnostic (same tenant, same permissions for every
# app), so a single registration serves all Hoist apps in a tenant. This keeps the Entra
# footprint at one artifact org-wide (or one per environment tier, if preferred) and keeps
# the tenant-wide-read secret out of the public sign-in clients. See "Registration topology".
appId=$(az ad app create --display-name "xh-hoist-directory-reader" --query appId -o tsv)
az ad sp create --id $appId

# Graph appId 00000003-0000-0000-c000-000000000000; =Role marks application permissions.
az ad app permission add --id $appId \
  --api 00000003-0000-0000-c000-000000000000 \
  --api-permissions \
    98830695-27a2-44f7-8c18-0c3ebc9698f6=Role \
    df021288-bdef-4463-88db-98f22de89214=Role   # GroupMember.Read.All, User.Read.All

az ad app permission admin-consent --id $appId
az ad app credential reset --id $appId --display-name "graph-secret" --years 1 --append
# -> prints the client secret once; store in xhEntraIdClientSecret
```

Then in Toolbox (running with Entra auth mode or not - the directory service is independent of
auth provider): set `xhEntraIdConfig` = `{"enabled": true, "tenantId": "<XH tenant>",
"clientId": "<appId>"}`, set `xhEntraIdClientSecret`, create a test group with members in the
XH tenant, and assign its object ID to a role via the admin console.

## Registration topology (multi-app deployments)

Deployments with many Hoist apps (25+ apps x multiple environments at one known client) should
use ONE shared directory-reader registration per tenant (or per environment tier), not per-app
registrations:

- App-only directory reads are identical for every app - no per-app identity requirement, unlike
  sign-in registrations (redirect URIs, audience).
- One registration = one admin-consent action, one secret (or cert) to manage. Reusing each
  app's sign-in registration would instead mean touching every registration to add permissions,
  consent, and a credential - a much larger burden on infra teams.
- All apps carry the same `tenantId` / `clientId` / secret in config. Entra supports multiple
  concurrent secrets per registration, so rotation can be staged across apps without outage.
- Trade-off to confirm with security teams: shared credential = shared blast radius (though the
  read scope is tenant-wide either way), and Graph audit logs attribute all Hoist directory
  reads to one app identity rather than per-app.

## Client environment - confirmed

- Commercial cloud: `login.microsoftonline.com` issuer confirmed via a sample client JWT, so
  standard `graph.microsoft.com` endpoints.
- Network egress: app servers can reach `login.microsoftonline.com` and `graph.microsoft.com`.
- Scale: directory is a few hundred users - no group-size or paging concerns.
- Username mapping: client JWTs carry UPN-form `email` / `preferred_username`, and Hoist
  usernames are the local part. Plan: `usernameAttribute: 'userPrincipalName'` +
  `stripUsernameDomain: true`. Believed correct - verify during rollout that it holds for all
  users, including any cloud-only accounts.

Also noted from the sample JWT: the `oid` claim (the user's stable Entra object ID) is present.
`EntraIdService.lookupUser` accepts an object ID, so apps can resolve the authenticated user to
a full Graph user record by `oid` - a rename-proof join that sidesteps UPN/email drift entirely.
A strong candidate for the client's "no authoritative user-base source" gap.

## Requests and open questions for client teams

1. Registration request (infra team): ONE new app registration, shared by all Hoist apps in the
   tenant (per "Registration topology" above), with Microsoft Graph **application**
   permissions `GroupMember.Read.All` + `User.Read.All`, admin consent granted up front. Hoist
   apps then need its client ID plus a client secret (or certificate - see #2) via soft config.
2. Credential policy (security team): is a client secret acceptable for app-only Graph read
   access, or is a certificate mandated? (Certificate support is a planned fast-follow if
   required.)
3. Hidden membership (infra team): will any groups used for app role assignments have "hidden
   membership" enabled (an M365 group option)? If so, the registration also needs the
   `Member.Read.Hidden` permission - our default set cannot read those memberships.

## Follow-ups (not in v1)

- Certificate credential support (config shape + `createFromCertificate` wiring).
- Honor `Retry-After` on 429s (needs `JSONClient` header surfacing).
- Bulk user listing / paged enumeration, if a concrete need lands.
- hoist-react Roles admin UI: displayName relay + group search (companion project).
- Feature doc under `docs/` + doc-registry sync once the API settles.
- Outbound proxy support, if required by client network topology.
