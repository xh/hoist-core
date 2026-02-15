# Authorization

## Overview

Hoist's authorization system enforces role-based access control across all controller endpoints.
Every request is checked by `AccessInterceptor` against annotations that declare which roles are
required. Roles are managed by a `RoleService` that applications provide — either by using the
built-in `DefaultRoleService` (database-backed with Admin Console UI) or by implementing a custom
role assignment strategy.

This system works in concert with [authentication](./authentication.md) — authentication
establishes *who* the user is, authorization determines *what* they can do.

## Source Files

| File | Location | Role |
|------|----------|------|
| `BaseRoleService` | `src/main/groovy/io/xh/hoist/role/` | Abstract role service contract |
| `DefaultRoleService` | `src/main/groovy/io/xh/hoist/role/provided/` | Database-backed implementation with Admin UI |
| `DefaultRoleAdminService` | `grails-app/services/io/xh/hoist/role/provided/` | Admin Console read operations (role listing with effective memberships) |
| `DefaultRoleUpdateService` | `grails-app/services/io/xh/hoist/role/provided/` | Role mutations (CRUD, `ensureRequiredRolesCreated`, `assignRole`) |
| `RoleAdminController` | `grails-app/controllers/io/xh/hoist/admin/` | Admin Console endpoints for role management |
| `Role` | `grails-app/domain/io/xh/hoist/role/provided/` | GORM domain — role definitions |
| `RoleMember` | `grails-app/domain/io/xh/hoist/role/provided/` | GORM domain — role memberships |
| `AccessInterceptor` | `grails-app/controllers/io/xh/hoist/security/` | Grails interceptor enforcing annotations |
| `@AccessRequiresRole` | `src/main/groovy/io/xh/hoist/security/` | Single role annotation |
| `@AccessRequiresAnyRole` | `src/main/groovy/io/xh/hoist/security/` | Any-of-roles annotation |
| `@AccessRequiresAllRoles` | `src/main/groovy/io/xh/hoist/security/` | All-of-roles annotation |
| `@AccessAll` | `src/main/groovy/io/xh/hoist/security/` | Open access annotation |
| `@Access` | `src/main/groovy/io/xh/hoist/security/` | Deprecated legacy annotation (equivalent to `@AccessRequiresAllRoles`) |

## Architecture

### Access Control Flow

When a request reaches the `AccessInterceptor` (after authentication in `HoistFilter`):

1. The interceptor resolves the controller action to a Java `Method` object.
2. It searches for an access annotation — first on the method, then on the controller class.
3. The first annotation found is evaluated against the current user's roles.
4. If the check passes, the request proceeds. If not, a `NotAuthorizedException` (403) is thrown.

Method-level annotations take precedence over class-level annotations, allowing a restrictive class
default with more permissive (or more restrictive) overrides on specific actions.

### Role Resolution

The `HoistUser.getRoles()` method delegates to `RoleService.getRolesForUser()` to determine which
roles a user has. This is called during annotation evaluation and is also serialized to the
hoist-react client for client-side role checks.

## Key Classes

### Access Annotations

Every controller endpoint **must** have one of these annotations (on either the method or the
class) or the request will fail:

#### `@AccessRequiresRole`

Requires the user to have a specific single role:

```groovy
@AccessRequiresRole('TRADE_MANAGER')
def executeTrade() { /* ... */ }
```

#### `@AccessRequiresAnyRole`

Requires the user to have at least one of the specified roles:

```groovy
@AccessRequiresAnyRole(['TRADER', 'TRADE_MANAGER', 'TRADE_VIEWER'])
def viewTrades() { /* ... */ }
```

#### `@AccessRequiresAllRoles`

Requires the user to have all of the specified roles:

```groovy
@AccessRequiresAllRoles(['REVIEWER', 'APPROVER'])
def approveReview() { /* ... */ }
```

#### `@AccessAll`

Allows any authenticated user, regardless of roles:

```groovy
@AccessAll
def healthCheck() { /* ... */ }
```

#### `@Access` (Deprecated)

Legacy annotation equivalent to `@AccessRequiresAllRoles`. Use the newer, more explicit annotations
instead.

### Typical Controller Pattern

```groovy
@AccessRequiresRole('APP_USER')
class PositionController extends BaseController {

    // Inherits APP_USER requirement from class annotation
    def listPositions() { /* ... */ }

    // Overrides class annotation with stricter requirement
    @AccessRequiresRole('TRADE_MANAGER')
    def closePosition() { /* ... */ }

    // Open to all authenticated users despite class annotation
    @AccessAll
    def getVersion() { /* ... */ }
}
```

### BaseRoleService

Abstract base that applications must extend (directly or via `DefaultRoleService`). Defines the
contract for role assignment queries.

#### Abstract Methods

| Method | Returns | Description |
|--------|---------|-------------|
| `getAllRoleAssignments()` | `Map<String, Set<String>>` | Map of role name → assigned usernames |
| `getRolesForUser(username)` | `Set<String>` | All roles for a specific user |
| `getUsersForRole(role)` | `Set<String>` | All users with a specific role |

These methods are called frequently and should be fast — backed by caching if the underlying
data source is expensive.

### DefaultRoleService

The recommended implementation for most applications. Provides database-backed role management with
full Admin Console support, LDAP/directory group integration, and role inheritance.

#### Features

- **Database storage** — Roles stored as `Role` and `RoleMember` GORM domain objects.
- **Admin Console UI** — Full CRUD for roles and memberships via the Hoist Admin Console.
- **Role inheritance** — Roles can include other roles as members, enabling hierarchical structures.
- **Directory group integration** — Roles can include LDAP/Active Directory groups, automatically
  resolving group members.
- **Cluster-safe caching** — Role assignments are cached in a replicated `CachedValue` and
  refreshed on a configurable timer.

#### Using DefaultRoleService

The simplest approach — extend it as your app's `RoleService`:

```groovy
// In grails-app/services/com/myapp/RoleService.groovy
package com.myapp

import io.xh.hoist.role.provided.DefaultRoleService

class RoleService extends DefaultRoleService {

    protected void ensureRequiredConfigAndRolesCreated() {
        super.ensureRequiredConfigAndRolesCreated()

        // Create app-specific roles (no-op if they already exist)
        ensureRequiredRolesCreated([
            [name: 'APP_USER', category: 'App', notes: 'Standard application access', roles: ['APP_ADMIN']],
            [name: 'APP_ADMIN', category: 'App', notes: 'Full admin access'],
            [name: 'TRADER', category: 'Trading', notes: 'Can execute trades']
        ])
    }
}
```

Alternatively, if no customization is needed, register `DefaultRoleService` directly as a Spring
bean via `grails-app/conf/spring/resources.groovy`:

```groovy
beans = {
    roleService(DefaultRoleService)
}
```

#### Role Inheritance

Roles can include other roles as members, creating an inheritance hierarchy. The `roles` field on a
role definition means "members of these listed roles also get this role":

```groovy
ensureRequiredRolesCreated([
    [name: 'APP_USER',  category: 'App', roles: ['APP_ADMIN']],  // admins also get APP_USER
    [name: 'APP_ADMIN', category: 'App'],
])
```

In this example, `APP_USER` lists `APP_ADMIN` in its `roles` field, meaning all `APP_ADMIN` members
automatically receive `APP_USER` as well.

#### Directory Group Integration

Roles can include LDAP/Active Directory groups. `DefaultRoleService` resolves group membership
via `LdapService`:

```groovy
ensureRequiredRolesCreated([
    [name: 'APP_USER', directoryGroups: ['CN=AppUsers,OU=Groups,DC=company,DC=com']]
])
```

Override `doLoadUsersForDirectoryGroups()` to integrate with non-LDAP directory services.

#### Customization Points

`DefaultRoleService` provides several protected properties and methods that subclasses can override:

| Property / Method | Default | Description |
|-------------------|---------|-------------|
| `getUserAssignmentSupported()` | `true` | Set to `false` to disable direct user-to-role assignment |
| `getDirectoryGroupsSupported()` | `true` | Set to `false` to disable directory group membership |
| `getDirectoryGroupsDescription()` | LDAP DN instructions | Short string for Admin Console tooltip |
| `doLoadUsersForDirectoryGroups()` | LDAP via `LdapService` | Override to resolve groups from non-LDAP sources |

#### Configuration

`DefaultRoleService` creates and uses the `xhRoleModuleConfig` soft configuration:

```json
{
  "refreshIntervalSecs": 300
}
```

This controls how often the role assignment cache is rebuilt from external sources such as directory
groups. Changes to roles made via the Admin Console trigger an immediate cache rebuild, and the
result is propagated to all cluster instances via the replicated `CachedValue`. The timer interval
primarily governs how quickly changes to external directory group memberships are picked up.

#### Bootstrap Admin User

For local development, you can configure an initial admin user via instance config:

```yaml
bootstrapAdminUser: dev.user
```

This user will always have the `HOIST_ADMIN`, `HOIST_ADMIN_READER`, and `HOIST_ROLE_MANAGER` roles,
even if no roles are configured in the database. This feature is restricted by the framework to
local development environments only (`isLocalDevelopment && !isProduction`) and has no effect in
production.

### Role and RoleMember Domains

#### Role

| Property | Type | Description |
|----------|------|-------------|
| `name` | `String` | Unique role name (primary key, case-insensitive) |
| `category` | `String` | Optional grouping for Admin Console display |
| `notes` | `String` | Optional description |
| `lastUpdated` | `Date` | Timestamp of last change |
| `lastUpdatedBy` | `String` | User who made the last change |
| `members` | `Set<RoleMember>` | Role memberships (cascade delete) |

Stored in table `xh_role`.

#### RoleMember

| Property | Type | Description |
|----------|------|-------------|
| `type` | `enum` | `USER`, `DIRECTORY_GROUP`, or `ROLE` |
| `name` | `String` | Username, directory group DN, or role name |
| `dateCreated` | `Date` | When the membership was created |
| `createdBy` | `String` | Who created the membership |

Stored in table `xh_role_member`. Unique constraint on `(type, role, name)`.

### Built-in Roles

Hoist defines four built-in roles that `DefaultRoleService` creates automatically:

| Role | Purpose |
|------|---------|
| `HOIST_ADMIN` | Full access to the Hoist Admin Console |
| `HOIST_ADMIN_READER` | Read-only Admin Console access (lists `HOIST_ADMIN` in its `roles` field) |
| `HOIST_IMPERSONATOR` | Can impersonate other users (lists `HOIST_ADMIN` in its `roles` field) |
| `HOIST_ROLE_MANAGER` | Can manage roles and memberships in the Admin Console |

Note that `HOIST_ADMIN_READER` and `HOIST_IMPERSONATOR` both list `HOIST_ADMIN` in their `roles`
field. This means all `HOIST_ADMIN` users automatically receive these less-privileged roles as well,
ensuring admins have read-only access and impersonation capabilities. `HOIST_ROLE_MANAGER` is
intentionally independent — `HOIST_ADMIN` does *not* automatically grant role management, so this
capability must be explicitly assigned.

## Application Implementation

### Using DefaultRoleService (Recommended)

Most applications should extend `DefaultRoleService` and create app-specific roles in
`ensureRequiredConfigAndRolesCreated()`. This gives you:

- Database-backed role storage
- Full Admin Console UI for managing roles
- Optional LDAP integration
- Role inheritance
- Cluster-safe caching

### Custom RoleService

For applications with external role systems (e.g., roles sourced from an OAuth provider or external
database), extend `BaseRoleService` directly:

```groovy
class RoleService extends BaseRoleService {

    private CachedValue<Map<String, Set<String>>> roleAssignments

    void init() {
        roleAssignments = createCachedValue(name: 'roleAssignments', replicate: true)
        createTimer(
            name: 'refreshRoles',
            runFn: this.&refresh,
            interval: 5 * MINUTES,
            primaryOnly: true
        )
    }

    Map<String, Set<String>> getAllRoleAssignments() {
        roleAssignments.get() ?: [:]
    }

    Set<String> getRolesForUser(String username) {
        allRoleAssignments.findAll { it.value.contains(username) }.keySet()
    }

    Set<String> getUsersForRole(String role) {
        allRoleAssignments[role] ?: [] as Set
    }

    private void refresh() {
        roleAssignments.set(loadRolesFromExternalSystem())
    }
}
```

## Common Patterns

### Checking Roles in Service Code

While annotations handle controller-level access, services can check roles directly via the user:

```groovy
class TradeService extends BaseService {

    void executeTrade(Trade trade) {
        if (!user.hasRole('TRADE_MANAGER')) {
            throw new NotAuthorizedException('Insufficient permissions to execute trades')
        }
        // Execute the trade...
    }
}
```

### Programmatic Role Assignment

`DefaultRoleService` provides `assignRole()` for programmatic assignment:

```groovy
class BootStrap {
    def roleService

    def init = {
        roleService.assignRole(someUser, 'APP_ADMIN')
    }
}
```

## Client Integration

Role assignments are sent to the hoist-react client as part of the identity response from
`IdentityService.getClientConfig()`. The client receives a `roles` set (or `apparentUserRoles` /
`authUserRoles` during impersonation) and can check roles via `XH.getUser().hasRole()`.

This enables client-side UI decisions (showing/hiding buttons, menus) while the server enforces
actual access control via annotations.

See [`authentication.md`](./authentication.md) for the full identity response structure.

## Common Pitfalls

### Relying on client-side role checks for security

Client-side role checks in hoist-react are for UI convenience only. The server's
`AccessInterceptor` is the actual security enforcement. Always annotate controller endpoints
regardless of client-side checks.

### Case sensitivity in usernames

`DefaultRoleService` lowercases usernames when storing `RoleMember` entries of type `USER`. The
`getRolesForUser()` lookup is also case-insensitive. However, custom `RoleService` implementations
should be careful to normalize case consistently.

### Stale directory group memberships

`DefaultRoleService` refreshes its cache on a timer (default 300 seconds). Role changes made via
the Admin Console trigger an immediate rebuild that propagates to all cluster instances. However,
changes to external directory group memberships are only picked up on the next timer cycle. For
development, reduce the `refreshIntervalSecs` in `xhRoleModuleConfig`, or use the Admin Console's
"Clear Caches" action to force a rebuild.

### Not creating required roles in `ensureRequiredConfigAndRolesCreated()`

If you reference roles in `@AccessRequiresRole` annotations but don't create them in
`ensureRequiredConfigAndRolesCreated()`, the application will start but no users will have those
roles. Always declare app-specific roles during service initialization.
