# Directory Services (LDAP & Microsoft Entra ID)

## Overview

Hoist can query an external corporate directory for users, groups, and group memberships. The
framework ships two directory service implementations:

- **`LdapService`** - queries one or more LDAP servers, typically Microsoft Active Directory.
- **`EntraIdService`** - queries a Microsoft Entra ID tenant via the Microsoft Graph API.

Both implement the narrow **`DirectoryService`** interface, which models what Hoist's built-in
role management requires. A directory service supports three concerns in the Hoist ecosystem:

1. **Authorization** - [`DefaultRoleService`](./authorization.md) resolves "directory group"
   role memberships through an enabled `DirectoryService`. This is the primary integration.
2. **User identity** - the `usernameAttribute` config keys map directory records to Hoist
   usernames, so directory results line up with [`HoistUser.username`](./authentication.md).
3. **Authentication support** - `LdapService.authenticate()` validates a username and password
   with an LDAP bind, to support form-based login as a backup to SSO.

Each service also has a richer, provider-specific query API for direct application use.
An application enables a directory service with its soft-config. Neither is required - apps
without an external directory can skip both and assign roles to users directly.

## Source Files

| File | Location | Role |
|------|----------|------|
| `DirectoryService` | `src/main/groovy/io/xh/hoist/directory/` | Common interface for group resolution |
| `LdapService` | `grails-app/services/io/xh/hoist/ldap/` | LDAP / Active Directory implementation |
| `LdapConfig` | `src/main/groovy/io/xh/hoist/ldap/` | Typed view of the `xhLdapConfig` soft-config |
| `LdapObject`, `LdapPerson`, `LdapGroup` | `src/main/groovy/io/xh/hoist/ldap/` | Typed LDAP query results - extend to add attributes |
| `EntraIdService` | `grails-app/services/io/xh/hoist/entra/` | Microsoft Entra ID implementation |
| `EntraIdConfig` | `src/main/groovy/io/xh/hoist/entra/` | Typed view of the `xhEntraIdConfig` soft-config |
| `EntraUser`, `EntraGroup` | `src/main/groovy/io/xh/hoist/entra/` | Typed Graph query results, with a fixed field set |
| `ErrorOr` | `src/main/groovy/io/xh/hoist/util/` | Value-or-error holder for batch results |

See the Groovydoc on these classes for full signatures and per-method detail.

## Architecture

### The DirectoryService Interface

`DirectoryService` is deliberately narrow. It models only what role resolution and its Admin
Console UI require:

| Method | Purpose |
|--------|---------|
| `getEnabled()` | True if the service is configured for use |
| `getDirectoryGroupsDescription()` | Hint text shown as placeholder in the Admin Console group picker |
| `loadUsersForDirectoryGroups(groups, strictMode)` | Resolve group identifiers to member usernames, nested groups included |
| `describeDirectoryGroups(groups)` | Display info (`displayName`) for groups already assigned to roles |
| `searchDirectoryGroups(namePart)` | Search groups by partial name, for the Admin Console group picker |

Two contract points apply to all implementations:

- **Disabled services throw.** The group resolution methods throw if called when `enabled` is
  false. Callers must check the `enabled` flag first. `DefaultRoleService` does this check and
  degrades gracefully, so a misconfigured app keeps a usable roles Admin Console.
- **Per-group failures are data, not exceptions.** The batch methods return
  `Map<String, ErrorOr<...>>`. Each entry holds either a result value or a String error
  description (for example `'Directory Group not found'`). One bad group does not fail the
  batch. `ErrorOr` serializes to JSON as the bare value or error String, so API responses stay
  simple for the client.

The `strictMode` flag on `loadUsersForDirectoryGroups` controls infrastructure failures. When
true, implementations throw on any partial failure. When false, they log the failure and
return an error description for the groups they could not load. `DefaultRoleService` uses
non-strict mode until it holds a complete result, then strict mode, so a directory outage
never replaces good cached role data with partial data.

### Provider Selection

`DefaultRoleService` selects one enabled implementation via the `directoryGroupProvider` key
in its `xhRoleModuleConfig` soft-config:

| Value | Behavior |
|-------|----------|
| `auto` (default) | Use whichever single implementation is enabled |
| `ldap` | Always use `LdapService` |
| `entraId` | Always use `EntraIdService` |

When both services are enabled under `auto`, the framework logs an ERROR and uses
`LdapService`. This makes sure an app that experiments with Entra ID does not silently move
its role memberships to the new source. Set `directoryGroupProvider` explicitly to resolve
the conflict.

Applications with a different external source can override
`DefaultRoleService.doLoadUsersForDirectoryGroups()` - see
[`authorization.md`](./authorization.md#directory-group-integration).

### Group Identifiers

The two providers identify groups differently. The stored identifier on a role is
provider-specific:

- **LDAP** - the full Distinguished Name (DN), for example
  `CN=AppUsers,OU=Groups,DC=company,DC=com`.
- **Entra ID** - the group object ID, a GUID that is stable through group renames.

The Admin Console shows a display name for each assigned group and searches the directory by
name, so admins do not have to work with raw DNs or GUIDs directly.

## Configuration

### LDAP

`LdapService` requires three soft-configs:

**`xhLdapConfig`** (json) - connection and query settings. Key entries:

| Key | Default | Description |
|-----|---------|-------------|
| `enabled` | `false` | Master switch |
| `servers` | `[]` | List of `{host, baseUserDn, baseGroupDn}` entries, queried in order |
| `usernameAttribute` | `samaccountname` | Person attribute mapped to the Hoist username |
| `timeoutMs` | `60000` | Per-query timeout |
| `cacheExpireSecs` | `300` | Query result cache duration |
| `useMatchingRuleInChain` | `false` | Use the AD-specific server-side operator for nested group membership, instead of recursive queries |
| `skipTlsCertVerification` | `false` | Disable TLS certificate checks (dev only) |

- **`xhLdapUsername`** (string) - DN of the user to bind as when querying.
- **`xhLdapPassword`** (pwd) - password for the query user.

See `LdapConfig` for the full option set and defaults.

### Entra ID

`EntraIdService` uses app-only (client credentials) auth against Microsoft Graph. It requires
an Entra ID app registration with these Microsoft Graph **application** permissions, granted
with admin consent:

- `GroupMember.Read.All` - group lookups and transitive membership resolution.
- `User.Read.All` - user lookups.

These soft-configs supply the connection:

**`xhEntraIdConfig`** (json) - query settings and master switch. Key entries:

| Key | Default | Description |
|-----|---------|-------------|
| `enabled` | `false` | Master switch |
| `usernameAttribute` | `userPrincipalName` | Graph user field mapped to the Hoist username |
| `stripUsernameDomain` | `false` | Strip the domain, so `jdoe@example.com` maps to `jdoe` |
| `timeoutMs` | `10000` | Per-request timeout, applied to each page of a paged result |
| `cacheExpireSecs` | `300` | Query result cache duration |

- **`xhEntraTenantId`** (string) - tenant ID (GUID) of the Entra ID tenant.
- **`xhEntraClientId`** (string) - client ID (GUID) of the app registration.

Plus one of two credentials for the app registration:

- **`xhEntraClientPfx`** (pwd) + **`xhEntraClientPfxPassword`** (pwd) - a base64-encoded
  PKCS#12 (`.pfx`/`.p12`) bundle holding the certificate uploaded to the app registration,
  its chain (if any), and its private key, plus the bundle's password (unset for a
  passwordless bundle). The recommended credential type -
  [Microsoft's guidance](https://learn.microsoft.com/en-us/entra/identity-platform/how-to-add-credentials)
  is to use certificates rather than client secrets in production. With a certificate, the
  private key never leaves the server: MSAL signs a short-lived JWT client assertion with it,
  and Entra verifies the signature against the uploaded certificate.
- **`xhEntraClientSecret`** (pwd) - a client secret. Simpler to provision, but a shared
  password sent with every token request. Not used when a certificate is configured.

PKCS#12 is the standard container for exactly this material in transit, and its base64 form
travels safely through secret managers and environment variables as a single-line string.
Parsing is handled by MSAL and the JDK's built-in PKCS#12 support - Hoist adds no certificate
parsing of its own. Enterprise PKI teams commonly issue `.pfx` bundles directly; to build one
from PEM material instead:

```bash
openssl pkcs12 -export -in cert.pem -inkey key.pem -out client.pfx
base64 -i client.pfx   # value for xhEntraClientPfx
```

Note MSAL sends the certificate (the `x5c` header) with each token request by default, so
Subject Name + Issuer-based trust and thumbprint-free certificate rotation work without
further configuration.

The tenant ID and client ID are standalone configs. Other server-side subsystems can share
them, and deployments can override them per environment via instance configs / environment
variables (e.g. `APP_MYAPP_XH_ENTRA_TENANT_ID`). See
[`configuration.md`](./configuration.md) for the instance config override mechanism. Apps
whose clients need these values pre-auth (e.g. for OAuth login) should relay them via their
`AuthenticationService.getClientConfig()` - see
[`authentication.md`](./authentication.md).

The service acquires a Graph access token at startup when enabled, so credential problems
appear in the log immediately. A failure there does not block startup - queries retry token
acquisition on demand.

## Username Mapping

Both services map directory records to Hoist usernames through their `usernameAttribute`
config key. This mapping is what connects directory group members to
[`HoistUser.username`](./authentication.md) values in the role system:

- Mapped values are lowercased, to match the Hoist convention that usernames are lowercase.
- Group members with no value for the attribute are excluded from role resolution.
- `LdapService.lookupUser()` and `LdapService.authenticate()` also match their username
  argument against this attribute, so user lookup, authentication, and role resolution all
  agree on what a username means.
- For Entra ID, the default `userPrincipalName` is an email-format sign-in name. Set
  `stripUsernameDomain: true` when Hoist usernames are the local part only. This also works
  for cloud-only accounts, which have no on-premises `sAMAccountName`.

## Role Management Integration

`DefaultRoleService` is the primary consumer. The integration works as follows:

1. On the primary cluster instance, a timer collects every distinct directory group assigned
   to any role and resolves them in one `loadUsersForDirectoryGroups` call. The timer runs at
   startup and every `refreshIntervalSecs` (default 300). Role changes made in the Admin
   Console trigger an immediate refresh.
2. Resolved memberships merge with direct user assignments and role inheritance to produce
   the effective role assignments, replicated across the cluster.
3. The Admin Console uses `describeDirectoryGroups` to show display names,
   `searchDirectoryGroups` to back its group picker, and per-group error descriptions to show
   inline warnings on groups that fail to resolve.

Because per-group failures return as data, a deleted group or a directory outage shows up as
a warning icon on the affected group rows - the roles UI stays functional and the rest of the
app's role assignments stay intact.

## Direct Query APIs

Beyond the `DirectoryService` contract, each service has provider-specific query methods for
application use. Consult the Groovydoc for signatures - highlights:

**`LdapService`**

- `lookupUser(username)`, `lookupGroups(dns)`, `lookupGroupMembers(dns)` - single and batch
  lookups. Nested group members are included.
- `findGroups(sNamePart)` - search groups by partial account name or CN (substring match).
- `searchOne(filter, objType, strictMode)` / `searchMany(...)` - general LDAP queries that
  return typed results. Pass `LdapPerson`, `LdapGroup`, or an application subclass. Subclass
  `LdapObject` types and override `getKeys()` to fetch additional attributes.
- `authenticate(username, password)` - validate credentials with an LDAP bind. This supports
  a form-based login strategy (see
  [`BaseAuthenticationService.login()`](./authentication.md)) and does not itself create an
  authenticated session.

**`EntraIdService`**

- `lookupUser(idOrUpn)` / `lookupUsers(idsOrUpns)` - accepts object IDs or userPrincipalNames.
- `findUsers(field, value)` - exact-match lookup on any String-typed `EntraUser` field, for
  reverse lookups from other identifiers (e.g. an on-prem SID or sAMAccountName). The
  `findUser` singular variant supports identity binding - it returns the one match or null,
  and throws rather than pick from an ambiguous multi-match.
- `lookupGroup(id)`, `lookupGroups(ids)`, `lookupGroupMembers(id | ids)` - lookups by group
  object ID. Graph resolves nested memberships server-side.
- `findGroups(namePart)` - search groups by display name. Matching is tokenized - each word or
  separator-delimited segment of the name matches by prefix.
- Results return as typed `EntraUser` / `EntraGroup` objects with a fixed set of Graph
  fields. The service does not currently support custom subclasses - apps that need other
  Graph fields can query Graph directly.

## Performance & Robustness

- **Caching** - both services cache query results for `cacheExpireSecs` (default 300). Config
  changes clear the caches automatically.
- **Bounded parallelism** - batch lookups run in parallel, in batches of at most 25. Typical
  workloads (a modest number of distinct groups across all roles) run in a single
  fully-parallel batch. The bound is a backstop against thread and connection storms from
  very large group sets.
- **LDAP partial results** - non-strict queries log and skip a server that fails to respond,
  so callers can receive partial results across a multi-server config.
- **Graph retries** - Entra ID requests retry a bounded number of times on throttling
  (HTTP 429) and server errors, with backoff. Paged results follow Graph `nextLink` paging.
- **Nested groups** - LDAP resolves nested membership with recursive queries, or with the
  AD-specific `matchingRuleInChain` operator when configured. Graph resolves transitive
  membership server-side, from an eventually-consistent index - very recent membership
  changes can lag briefly.

## Common Pitfalls

### Both providers enabled without an explicit selection

With `directoryGroupProvider: 'auto'` and both services enabled, the framework logs an ERROR
and uses LDAP. Set the provider explicitly before you enable a second directory service.

### Switching providers orphans stored group identifiers

Roles store provider-specific group identifiers (LDAP DNs or Entra ID GUIDs). When an app
moves from one provider to the other, existing directory group assignments will not resolve.
The Admin Console flags them with per-group warnings. Re-enter the groups under the new
provider's identifier form.

### Invalid `usernameAttribute`

The attribute must be one of the fields whitelisted for username use
(`LdapObject.usernameKeys` / `EntraUser.usernameKeys`) - other fields, even valid query
fields, are rejected. Role resolution reports an invalid value as a per-group error
description.
`LdapService.lookupUser()` and `authenticate()` throw on an invalid value.

### Missing admin consent or expired credential (Entra ID)

Graph rejects app-only queries when the required application permissions lack admin consent,
and token acquisition fails when the client secret or certificate expires. Both appear in the
startup log - `EntraIdService` warms its token at startup for exactly this reason. Client
secrets have a maximum lifetime of 24 months - track and rotate them. For certificates, the
service's Admin Console stats report the configured certificate's subject, thumbprint, and
expiry date, and an app registration can hold multiple certificates at once - upload the new
one before the old expires for zero-downtime rotation.

### Per-request directory lookups on the auth path

Do not resolve directory identity from the live directory on every request. Look up what the
app needs once per session and store it on the app's user object. The query caches soften
repeated lookups, but a directory outage that outlasts `cacheExpireSecs` will then fail
lookups - and an auth path that requires them fails with it. The framework's own role
resolution is not exposed to this - it retains its last good result through an outage.

### Stale group memberships

Directory group membership changes appear only on the next role refresh cycle
(`refreshIntervalSecs`, default 300 seconds), and query caches hold results for
`cacheExpireSecs`. For development, lower these values, or use the Admin Console "Clear
Caches" action to force a refresh.
