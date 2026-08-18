# Authentication

## Overview

Hoist's authentication system makes sure that every request that reaches application code has
an identified, active user. Applications implement their own authentication scheme (SSO,
OAuth, form-based, etc.) by extending two abstract services:

- **`AuthenticationService`** (extends `BaseAuthenticationService`) - defines how users prove
  their identity. Runs on every request via `HoistFilter`.
- **`UserService`** (extends `BaseUserService`) - defines how usernames resolve to `HoistUser`
  objects. Supplies user lists for impersonation and admin features.

The framework supplies `IdentityService` for access to the current user throughout a request,
the `HoistUser` trait as the user contract, and impersonation support for admin
troubleshooting.

This system works in concert with [authorization](./authorization.md) - authentication
establishes *who* the user is, authorization determines *what* they can do. Apps that back
their users with a corporate directory can also use a
[directory service](./directory-services.md) for user lookup, username mapping, and
password validation.

## Source Files

| File | Location | Role |
|------|----------|------|
| `BaseAuthenticationService` | `src/main/groovy/io/xh/hoist/security/` | Abstract auth service - app must extend |
| `BaseUserService` | `src/main/groovy/io/xh/hoist/user/` | Abstract user service - app must extend |
| `HoistUser` | `src/main/groovy/io/xh/hoist/user/` | Trait defining core user properties |
| `IdentityService` | `grails-app/services/io/xh/hoist/user/` | Current user access and impersonation |
| `HoistIdentity` | `src/main/groovy/io/xh/hoist/user/` | Immutable username + authUsername pair, stored on the session and per-thread |
| `IdentitySupport` | `src/main/groovy/io/xh/hoist/user/` | Interface with `getUser()` / `getUsername()` / `getAuthUser()` / `getAuthUsername()` - implemented by `BaseService` and `BaseController`, which delegate to `IdentityService` |

## Architecture

### Authentication Flow

```
Request arrives at HoistFilter
    │
    ├── User already in session OR URL whitelisted? ── Yes ──→ Pass through
    │
    └── No ──→ Call completeAuthentication()
                   │
                   ├── App calls setUser(request, hoistUser) ──→ User stored in session
                   │     └── Returns true ──→ Request continues
                   │
                   ├── Returns true but no user set ──→ 401 NotAuthenticatedException
                   │
                   └── Returns false ──→ Response halted (e.g. OAuth redirect in progress)
```

### Identity Storage and Propagation

The HTTP session is the durable source of truth for identity. It holds a single `xhIdentity`
attribute - a `HoistIdentity` with the apparent username and the authenticated username. The
two differ only during impersonation.

Identity accessors do not read the session directly. They read a per-thread `HoistIdentity`
cache, which the framework installs at each thread entry point:

- `HoistFilter` - at HTTP request entry, from the session.
- `HoistWebSocketHandler` - on WebSocket lifecycle callbacks, from handshake-captured
  attributes.
- `HoistPromiseFactory` - propagates the caller's identity into Grails `task {}` workers.
- `ClusterTask` - propagates identity across cluster boundaries for remote service calls.

Mutating operations (`login`, `logout`, `impersonate`, `endImpersonate`,
`noteUserAuthenticated`) update the session and the thread cache together.

The framework creates a session only when `noteUserAuthenticated()` stores a verified user.
All other session access uses `getSession(false)`, which returns `null` rather than create a
new session. This prevents denial-of-service attacks that exhaust server memory with sessions for
unauthenticated requests.

## Key Classes

### BaseAuthenticationService

The abstract service that applications extend to define their authentication scheme. The
framework calls `allowRequest()` on every request via `HoistFilter`. That method is not for
override - it checks for an existing session user or a whitelisted URL, calls the app's
`completeAuthentication()` when needed, and catches every exception. Failures are logged and
returned to the client as an opaque HTTP status, with no detail for unverified callers.

The app-facing contract:

| Method | Default | Override to... |
|--------|---------|----------------|
| `completeAuthentication(request, response)` | abstract | Implement the app's auth scheme - required |
| `login(request, username, password)` | returns `false` | Support interactive form-based login |
| `logout()` | returns `false` | Support explicit logout - clear app-specific auth state |
| `getClientConfig()` | empty map | Send auth config to the client before authentication |
| `isWhitelist(request)` | suffix match on `whitelistURIs` | Apply custom whitelist logic |

#### `completeAuthentication()`

The core method to implement:

```groovy
class AuthenticationService extends BaseAuthenticationService {

    protected boolean completeAuthentication(HttpServletRequest request,
                                             HttpServletResponse response) {
        // Example: read an SSO header and look up the user
        String ssoUsername = request.getHeader('X-SSO-User')
        if (ssoUsername) {
            def user = userService.find(ssoUsername)
            if (user) {
                setUser(request, user)
                return true
            }
        }
        // No auth info available - redirect to SSO login
        response.sendRedirect('/sso/login')
        return false
    }
}
```

Return values:

- `true` + `setUser()` called → request continues with the authenticated user.
- `true` + no `setUser()` → framework throws a 401 (authentication failed).
- `false` → the response is already handled (e.g. a redirect). Framework takes no action.

Call `setUser(request, hoistUser)` when a user is verified. It rejects inactive users with
`NotAuthorizedException` and stores the user in the session via
`IdentityService.noteUserAuthenticated()`.

#### Interactive login and logout

Apps with form-based login override `login()`. A common pattern validates the password with
an LDAP bind via [`LdapService.authenticate()`](./directory-services.md):

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

SSO-based applications leave the `false` defaults in place. The framework's `/xh/login` and
`/xh/logout` endpoints call these methods via `IdentityService`, which supplies the current
request and clears the session identity after a confirmed logout.

#### Whitelisted URIs

`whitelistURIs` lists URIs that bypass authentication: `/xh/ping` (and its legacy `/ping`
alias), `/xh/login`, `/xh/logout`, `/xh/version`, and `/xh/authConfig`. Subclasses can add
entries in their constructor or `init()`. The list deliberately excludes the client's
`authStatus` check URI - SSO apps need that request to reach `completeAuthentication()` so
they can install a user on the session.

### BaseUserService

Abstract service that applications extend to define user lookup and listing:

| Method | Contract |
|--------|----------|
| `find(username)` | Resolve a username to a `HoistUser`, or null. Called multiple times per request - must be fast |
| `list(activeOnly)` | Return all users, optionally active-only. Used by admin features and impersonation |
| `impersonationTargetsForUser(authUser)` | Users that `authUser` can impersonate. Default filters for safety |

Because `find()` runs multiple times per request, back it with a cache:

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

The default `impersonationTargetsForUser()` returns an empty list for users who cannot
impersonate and blocks non-admins from impersonating `HOIST_ADMIN` users. Overrides should
call `super` first - it prevents privilege escalation.

### HoistUser

A Groovy trait that defines the core properties and behaviors of every user object.
Application user classes implement this trait. `HoistUser` implements `JSONFormat` with a
default `formatForJSON()` that serializes `username`, `email`, `displayName`, and `active`.

Required (abstract) properties:

| Property | Type | Description |
|----------|------|-------------|
| `username` | `String` | Unique identifier - must be lowercase, no spaces |
| `email` | `String` | User's email address |
| `isActive` | `boolean` | Whether the user is active |

Provided properties and methods:

| Member | Description |
|--------|-------------|
| `displayName` | Human-readable name - defaults to `username` |
| `roles` | All assigned roles, from `RoleService` |
| `hasRole()` / `hasAnyRole()` / `hasAllRoles()` | Role checks - see [authorization](./authorization.md) |
| `isHoistAdmin` / `isHoistAdminReader` / `canImpersonate` | Built-in role checks |
| `hasGate(name)` | Lightweight feature gate, backed by soft-config |

A gate is a `string`-type config that holds a comma-delimited list of usernames, or `*` for
all users. `hasGate()` reads it via `ConfigService.getStringList()`. Gates restrict access to
features under development without the overhead of a dedicated role.

The static helper `HoistUser.validateUsername()` checks the username convention - lowercase,
no spaces. The framework does not enforce this at authentication time. Apps must make sure
their usernames satisfy it, as usernames key user preferences, tracking, and role
assignments.

### IdentityService

The framework service for access to the current user. Not for override.

| Method | Returns | Description |
|--------|---------|-------------|
| `getUser()` | `HoistUser` | Current apparent user (the impersonated user, if active) |
| `getUsername()` | `String` | Current apparent username |
| `getAuthUser()` | `HoistUser` | Authenticated user (ignores impersonation) |
| `getAuthUsername()` | `String` | Authenticated username |
| `isImpersonating()` | `boolean` | Whether impersonation is active |

These methods return `null` on threads with no installed identity - for example, a timer's
background thread that no request initiated. See
[Identity Storage and Propagation](#identity-storage-and-propagation) for where the framework
installs identity automatically.

`getClientConfig()` returns identity information for the client. The shape depends on
impersonation state:

- **Normal:** `{user, roles}` - the authenticated user and their roles.
- **Impersonating:** `{apparentUser, apparentUserRoles, authUser, authUserRoles}` - both
  users, so the client can display impersonation state.

### Impersonation

Impersonation lets administrators "become" another user for troubleshooting. The impersonated
user's roles, preferences, and identity apply to all subsequent requests.

```groovy
// Start impersonation (requires HOIST_IMPERSONATOR role)
identityService.impersonate('jane.doe')

// During impersonation:
identityService.user               // → jane.doe's HoistUser
identityService.authUser           // → original admin's HoistUser
identityService.isImpersonating()  // → true

// End impersonation
identityService.endImpersonate()
```

Security controls:

- The user must have the `HOIST_IMPERSONATOR` role.
- Impersonation must be enabled via the `xhEnableImpersonation` soft-config (boolean).
- Non-admins cannot impersonate users with the `HOIST_ADMIN` role.
- All impersonation events are tracked via `TrackService` with `WARN` severity.

## Application Implementation

Applications must create two concrete services and a user class:

```groovy
// grails-app/services/com/myapp/AuthenticationService.groovy
class AuthenticationService extends BaseAuthenticationService {

    def userService

    protected boolean completeAuthentication(HttpServletRequest request,
                                             HttpServletResponse response) {
        String username = extractUsernameFromSSOHeaders(request)
        if (username) {
            def user = userService.find(username)
            if (user) {
                setUser(request, user)
                return true
            }
        }
        return true  // Let the framework throw a 401
    }
}
```

```groovy
// grails-app/services/com/myapp/UserService.groovy
class UserService extends BaseUserService {

    HoistUser find(String username) {
        AppUser.findByUsername(username)
    }

    List<AppUser> list(boolean activeOnly) {
        activeOnly ? AppUser.findAllByActive(true) : AppUser.list()
    }
}
```

```groovy
// grails-app/domain/com/myapp/AppUser.groovy
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

1. The client calls `/xh/authConfig` to get `BaseAuthenticationService.getClientConfig()`.
2. The client calls `/xh/authStatus`. If the user has a valid session, the server returns
   identity info via `IdentityService.getClientConfig()`.
3. If no session exists, the server's `completeAuthentication()` handles the auth flow
   (redirect, challenge, etc.).
4. Once authenticated, the client receives roles, user info, and impersonation state.

## Common Pitfalls

### Slow `find()` implementation

`BaseUserService.find()` runs multiple times per request. A database query on every call will
severely impact performance. Always cache user lookups:

```groovy
// ✅ Do: cache user lookups
HoistUser find(String username) {
    userCache.getOrCreate(username) { AppUser.findByUsername(username) }
}

// ❌ Don't: query the database on every call
HoistUser find(String username) {
    AppUser.findByUsername(username)
}
```

### Duplicating the active-user check

`setUser()` rejects inactive users with `NotAuthorizedException`. Do not repeat this check in
`completeAuthentication()` - let the framework handle it.

### Leaking auth details to unauthenticated clients

`allowRequest()` deliberately returns opaque errors, with no stack traces or detailed
messages, to clients that are not authenticated. Do not override this method - put custom
logic in `completeAuthentication()`.

### Creating sessions on unauthenticated requests

Outside of `noteUserAuthenticated()`, `IdentityService` only reads existing sessions via
`getSession(false)`. Do not call `request.getSession(true)` in authentication code before you
verify the user. This prevents memory exhaustion from bots or scanners that hit
unauthenticated endpoints.
