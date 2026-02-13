> **Status: DRAFT** — This document is awaiting review. Content may be incomplete or subject to
> change. Do not remove this banner until the document has been interactively reviewed and approved.

# Authentication

## Overview

Hoist's authentication system ensures that every request reaching application code has an identified,
active user. Applications implement their own authentication scheme (SSO, OAuth, LDAP, form-based,
etc.) by extending two abstract services:

- **`AuthenticationService`** (extends `BaseAuthenticationService`) — Defines how users prove their
  identity. Runs on every request via `HoistFilter`.
- **`UserService`** (extends `BaseUserService`) — Defines how usernames resolve to `HoistUser`
  objects. Provides user lists for impersonation and admin features.

The framework provides `IdentityService` for accessing the current user throughout a request,
`HoistUser` as the user trait, and impersonation support for admin troubleshooting.

## Source Files

| File | Location | Role |
|------|----------|------|
| `BaseAuthenticationService` | `src/main/groovy/io/xh/hoist/security/` | Abstract auth service — app must extend |
| `BaseUserService` | `src/main/groovy/io/xh/hoist/user/` | Abstract user service — app must extend |
| `HoistUser` | `src/main/groovy/io/xh/hoist/user/` | Trait defining core user properties |
| `IdentityService` | `grails-app/services/io/xh/hoist/user/` | Current user access and impersonation |
| `IdentitySupport` | `src/main/groovy/io/xh/hoist/user/` | Interface for identity getter methods |

## Architecture

### Authentication Flow

```
Request arrives at HoistFilter
    │
    ├── Is URL whitelisted? ──── Yes ──→ Pass through (no auth needed)
    │
    ├── Is user already in session? ── Yes ──→ Pass through
    │
    └── No ──→ Call completeAuthentication()
                   │
                   ├── App calls setUser(request, hoistUser) ──→ User stored in session
                   │     └── Returns true ──→ Request continues
                   │
                   ├── Returns true but no user set ──→ 401 NotAuthenticatedException
                   │
                   └── Returns false ──→ Response halted (e.g., OAuth redirect in progress)
```

### Session Management

Authentication state is stored in the HTTP session with two keys:

- **`xhAuthUser`** — The authenticated username (verified by the auth scheme)
- **`xhApparentUser`** — The "active" username (same as auth user unless impersonating)

`IdentityService` never creates sessions — it only reads from existing sessions. This prevents
denial-of-service attacks that could exhaust server memory by creating sessions on unauthenticated
requests.

## Key Classes

### BaseAuthenticationService

The abstract service that applications must extend to define their authentication scheme. The
framework calls into this service on every request via `HoistFilter`.

#### `allowRequest(request, response)` — Framework Entry Point

Called on every request by `HoistFilter`. This method is **not intended for override** — it
orchestrates the authentication check:

1. Checks if the URL is whitelisted (no auth needed).
2. Checks if a user is already stored in the session.
3. If not, calls `completeAuthentication()` (the app's custom logic).
4. If authentication completes but no user is set, throws `NotAuthenticatedException`.
5. Catches all exceptions and returns opaque errors to unverified clients.

#### `completeAuthentication(request, response)` — App Must Implement

The core method applications override to implement their auth scheme:

```groovy
class AuthenticationService extends BaseAuthenticationService {

    protected boolean completeAuthentication(HttpServletRequest request,
                                             HttpServletResponse response) {
        // Example: Read SSO header and look up user
        String ssoUsername = request.getHeader('X-SSO-User')
        if (ssoUsername) {
            def user = userService.find(ssoUsername)
            if (user) {
                setUser(request, user)
                return true
            }
        }
        // No auth info available — redirect to SSO login
        response.sendRedirect('/sso/login')
        return false
    }
}
```

**Return values:**
- `true` + `setUser()` called → Request continues with authenticated user.
- `true` + no `setUser()` → Framework throws 401 (authentication failed).
- `false` → Response is already handled (e.g., redirect). Framework takes no action.

#### `setUser(request, hoistUser)` — Establish Session

Called by `completeAuthentication()` implementations when a user is verified. This method validates
that the user is active (throwing `NotAuthorizedException` if not) and stores the user in the
session via `IdentityService.noteUserAuthenticated()`.

#### `login(request, username, password)` — Interactive Login

For applications with form-based login. Default returns `false` (not supported). Override for
interactive auth:

```groovy
boolean login(HttpServletRequest request, String username, String password) {
    if (ldapService.authenticate(username, password)) {
        def user = userService.find(username)
        if (user) {
            setUser(request, user)
            return true
        }
    }
    return false
}
```

SSO-based applications leave the default implementation in place.

#### `logout()` — End Session

For applications supporting explicit logout. Default returns `false`. Override to return `true` after
clearing any auth state. `IdentityService` will clear the session keys when this returns `true`.

#### Whitelist URIs

`BaseAuthenticationService` maintains a list of URIs that bypass authentication:

- `/ping`, `/xh/ping` — Health checks
- `/xh/login`, `/xh/logout` — Auth flow endpoints
- `/xh/version` — Version info
- `/xh/authConfig` — Client auth configuration

Subclasses can extend this list by mutating `whitelistURIs` in their constructor or `init()`.

#### `getClientConfig()`

Returns a map of auth configuration to send to the client before authentication. This is used by
hoist-react's `XH.initAsync()` to configure the client auth flow. The data must be safe for public
visibility.

### BaseUserService

Abstract service that applications must extend to define user lookup and listing.

#### `find(username)` — Must Implement

Resolve a username to a `HoistUser` object. **This method is called multiple times per request**
and must be fast — ideally backed by an in-memory cache.

```groovy
class UserService extends BaseUserService {
    private Cache<String, AppUser> userCache

    void init() {
        userCache = createCache(name: 'users', expireTime: 5 * MINUTES)
    }

    HoistUser find(String username) {
        userCache.getOrCreate(username) {
            AppUser.findByUsername(username)
        }
    }

    List<AppUser> list(boolean activeOnly) {
        activeOnly ? AppUser.findAllByActive(true) : AppUser.list()
    }
}
```

#### `list(activeOnly)` — Must Implement

Return all users, optionally filtered to active-only. Used by admin features and impersonation
target lists.

#### `impersonationTargetsForUser(authUser)`

Returns the list of users that `authUser` can impersonate. The default implementation provides
important security filtering:

- Returns empty list if the user cannot impersonate.
- Non-admins cannot impersonate users with `HOIST_ADMIN` role.
- Admins can impersonate anyone.

**Strongly recommended** to call `super` if overriding — it prevents privilege escalation.

### HoistUser

A Groovy trait that defines the core properties and behaviors every user object must have.
Application user classes implement this trait.

#### Required Properties (Abstract)

| Property | Type | Description |
|----------|------|-------------|
| `username` | `String` | Unique identifier — must be lowercase, no spaces |
| `email` | `String` | User's email address |
| `isActive` | `boolean` | Whether the user is active |

#### Provided Properties

| Property | Type | Description |
|----------|------|-------------|
| `displayName` | `String` | Human-readable name (defaults to `username`) |
| `roles` | `Set<String>` | All assigned roles (from `RoleService`) |
| `isHoistAdmin` | `boolean` | Has `HOIST_ADMIN` role |
| `isHoistAdminReader` | `boolean` | Has `HOIST_ADMIN_READER` role |
| `canImpersonate` | `boolean` | Has `HOIST_IMPERSONATOR` role |

#### Role Checking Methods

```groovy
user.hasRole('APP_ADMIN')                      // single role
user.hasAnyRole('APP_ADMIN', 'APP_READER')     // at least one
user.hasAllRoles('REVIEWER', 'APPROVER')       // all required
```

#### Feature Gates

`HoistUser` provides a lightweight feature gating mechanism via `hasGate()`:

```groovy
if (user.hasGate('newDashboard')) {
    // Feature enabled for this user
}
```

Gates are sourced from soft-configuration — the config value is a list of usernames (or `*` for
all users).

#### Username Validation

`HoistUser.validateUsername()` enforces the convention that usernames must be lowercase with no
spaces. This is checked during authentication.

### IdentityService

The framework-provided service for accessing the current user. Not intended for override.

#### User Access

| Method | Returns | Description |
|--------|---------|-------------|
| `getUser()` | `HoistUser` | Current active user (impersonated if active) |
| `getUsername()` | `String` | Current active username |
| `getAuthUser()` | `HoistUser` | Authenticated user (ignores impersonation) |
| `getAuthUsername()` | `String` | Authenticated username |
| `isImpersonating()` | `boolean` | Whether impersonation is active |

All methods return `null` when called outside a request context (e.g., in a timer's background
thread that wasn't initiated by a request).

#### `getClientConfig()`

Returns identity information for the client. During impersonation, the response includes both the
apparent user (with their roles) and the auth user (with their roles), allowing the client to
display impersonation state.

### Impersonation

Impersonation allows administrators to "become" another user for troubleshooting — the impersonated
user's roles, preferences, and identity are used for all subsequent requests.

```groovy
// Start impersonation (requires HOIST_IMPERSONATOR role)
identityService.impersonate('jane.doe')

// During impersonation:
identityService.user          // → jane.doe's HoistUser
identityService.authUser      // → original admin's HoistUser
identityService.isImpersonating()  // → true

// End impersonation
identityService.endImpersonate()
```

**Security controls:**
- User must have `HOIST_IMPERSONATOR` role.
- Impersonation must be enabled via soft configuration.
- Non-admins cannot impersonate users with `HOIST_ADMIN` role.
- All impersonation events are tracked via `TrackService` with `WARN` severity.

## Application Implementation

Applications must create two concrete services:

### `AuthenticationService`

```groovy
package com.myapp

import io.xh.hoist.security.BaseAuthenticationService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse

class AuthenticationService extends BaseAuthenticationService {

    def userService

    protected boolean completeAuthentication(HttpServletRequest request,
                                             HttpServletResponse response) {
        // App-specific auth logic here
        String username = extractUsernameFromSSOHeaders(request)
        if (username) {
            def user = userService.find(username)
            if (user) {
                setUser(request, user)
                return true
            }
        }
        return true  // Let framework throw 401
    }
}
```

### `UserService`

```groovy
package com.myapp

import io.xh.hoist.user.BaseUserService
import io.xh.hoist.user.HoistUser

class UserService extends BaseUserService {

    HoistUser find(String username) {
        AppUser.findByUsername(username)
    }

    List<AppUser> list(boolean activeOnly) {
        activeOnly ? AppUser.findAllByActive(true) : AppUser.list()
    }
}
```

### AppUser Domain Class

```groovy
package com.myapp

import io.xh.hoist.user.HoistUser

class AppUser implements HoistUser {
    String username
    String email
    String displayName
    boolean active

    boolean isActive() { active }
}
```

## Client Integration

The authentication system integrates with hoist-react's initialization flow:

1. Client calls `/xh/authConfig` to get `BaseAuthenticationService.getClientConfig()`.
2. Client attempts to access `/xh/authStatus` — if the user has a valid session, the server returns
   identity info via `IdentityService.getClientConfig()`.
3. If no session exists, the server's `completeAuthentication()` handles the auth flow (redirect,
   challenge, etc.).
4. Once authenticated, the client receives roles, user info, and impersonation state.

## Common Pitfalls

### Slow `find()` implementation

`BaseUserService.find()` is called multiple times per request. A database query on every call will
severely impact performance. Always cache user lookups:

```groovy
// ✅ Do: Cache user lookups
HoistUser find(String username) {
    userCache.getOrCreate(username) { AppUser.findByUsername(username) }
}

// ❌ Don't: Query the database on every call
HoistUser find(String username) {
    AppUser.findByUsername(username)
}
```

### Inactive user not caught

`setUser()` checks that the user is active and throws `NotAuthorizedException` if not. Don't
duplicate this check in `completeAuthentication()` — let the framework handle it.

### Leaking auth details to unauthenticated clients

`allowRequest()` deliberately returns opaque errors (no stack traces or detailed messages) to
clients that haven't been authenticated. Don't override this method — use `completeAuthentication()`
for custom logic.

### Creating sessions on unauthenticated requests

`IdentityService` never creates sessions — it only reads existing ones. Don't call
`request.getSession(true)` in authentication code unless you've verified the user. This prevents
memory exhaustion from bots or scanners hitting unauthenticated endpoints.
