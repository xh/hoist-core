# Request Flow

## Overview

Every HTTP request in a Hoist application passes through a well-defined pipeline before reaching
application code. This pipeline handles instance readiness, authentication, URL routing, role-based
access control, and exception handling — ensuring that by the time a controller action executes, the
user is authenticated and authorized.

Understanding this flow is essential for debugging request failures, implementing custom
authentication, and knowing where to add cross-cutting concerns.

## Source Files

| File | Location | Role |
|------|----------|------|
| `HoistCoreGrailsPlugin` | `src/main/groovy/io/xh/hoist/` | Plugin descriptor — registers filter, initializes Hazelcast |
| `HoistFilter` | `src/main/groovy/io/xh/hoist/` | Servlet filter — auth gating, instance readiness, top-level exception catching |
| `UrlMappings` | `grails-app/controllers/io/xh/hoist/` | URL pattern routing |
| `HoistInterceptor` | `grails-app/controllers/io/xh/hoist/` | Grails interceptor — role annotation checks |
| `BaseController` | `grails-app/controllers/io/xh/hoist/` | Base controller — JSON rendering, exception handling |

## Architecture

### Request Pipeline

```
HTTP Request
    │
    ▼
┌─────────────────────────────┐
│  1. HoistFilter             │  Servlet filter (highest precedence)
│     • ensureRunning()       │  Verify instance is ready to serve
│     • allowRequest()        │  Authenticate user (or whitelist)
│     • catch exceptions      │  Top-level exception safety net
└─────────────────────────────┘
    │
    ▼
┌─────────────────────────────┐
│  2. UrlMappings             │  Grails URL routing
│     • /rest/$controller     │  REST CRUD endpoints
│     • /$controller/$action  │  Standard controller endpoints
│     • /proxy/$name/$url     │  Proxy pass-through
└─────────────────────────────┘
    │
    ▼
┌─────────────────────────────┐
│  3. HoistInterceptor       │  Grails interceptor (matches all)
│     • Find controller method│  Resolve action to a Method
│     • Check @Access* ann.   │  Evaluate role annotations
│     • 404 if no method      │  NotFoundException for bad routes
│     • 403 if not authorized │  NotAuthorizedException for role failures
│     • catch exceptions      │  Renders errors directly to response
└─────────────────────────────┘
    │
    ▼
┌─────────────────────────────┐
│  4. Controller Action       │  Application code
│     • parseRequestJSON()    │  Read request body
│     • Business logic        │  Process the request
│     • renderJSON()          │  Write JSON response
└─────────────────────────────┘
```

## Key Classes

### HoistCoreGrailsPlugin

The plugin descriptor bootstraps the entire Hoist server-side framework. Its key responsibilities
during startup:

1. **Configures logging** — Creates a `LogbackConfig` (app-customizable) before anything else.
2. **Initializes Hazelcast** — Calls `ClusterService.initializeHazelcast()` to start the
   Hazelcast instance. This is required even for single-instance deployments, as Hoist's caching
   and distributed data structures are built on Hazelcast.
3. **Registers `HoistFilter`** — As a `FilterRegistrationBean` at `HIGHEST_PRECEDENCE + 40`,
   ensuring it runs before Grails' built-in filters.
4. **Optionally enables WebSocket** — If `hoist.enableWebSockets` is `true` in application config.
5. **Registers `ExceptionHandler`** — A Spring bean for consistent exception rendering.

On shutdown, it orchestrates cleanup in order: sets instance state to `STOPPING`, shuts down all
timers, then shuts down Hazelcast.

### HoistFilter

The outermost entry point for all HTTP requests. Registered as a Jakarta `Filter` at very high
precedence so it wraps even Grails' internal filters.

```groovy
void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) {
    try {
        clusterService.ensureRunning()
        if (authenticationService.allowRequest(httpRequest, httpResponse)) {
            chain.doFilter(request, response)
        }
    } catch (Throwable t) {
        Utils.handleException(exception: t, renderTo: httpResponse, logTo: this)
    }
}
```

The filter performs three critical functions:

1. **Instance readiness** — `clusterService.ensureRunning()` verifies that the server instance is
   in a `RUNNING` state. If the instance is still starting up or shutting down, the request is
   rejected with an `InstanceNotAvailableException` before any other processing.

2. **Authentication gating** — `authenticationService.allowRequest()` first checks if the request
   already has an authenticated user (`identityService.findAuthUser(request)`) **or** if the
   request URI is whitelisted (`isWhitelist(request)`). Whitelisted endpoints — including `/ping`,
   `/xh/login`, `/xh/logout`, `/xh/ping`, `/xh/version`, `/xh/authConfig`, and `/xh/inventory` — are allowed
   through without authentication. If neither condition is met, the filter calls
   `completeAuthentication()` to attempt authentication. If `allowRequest()` returns `false`, the
   request is halted (e.g., during an OAuth redirect). See [`authentication.md`](./authentication.md)
   for details.

3. **Top-level exception handling** — Any uncaught exception from the rest of the request pipeline
   (e.g., `ensureRunning()` failures, Grails internals) is caught here and rendered as a JSON
   error response. Note that `allowRequest()` handles its own exceptions internally (see
   [Exception Handling in the Pipeline](#exception-handling-in-the-pipeline)).

### UrlMappings

Defines URL patterns that route requests to controller actions:

| Pattern | Routes To | Purpose |
|---------|-----------|---------|
| `/` | `DefaultController` (if app-provided) | Root redirect |
| `/$controller/$action?/$id?(.$format)?` | Standard dispatch | General controller endpoints |
| `/rest/$controller/$id?` | `POST→create, GET→read, PUT→update, DELETE→delete` | REST CRUD |
| `/rest/$controller/bulkUpdate` | `bulkUpdate` action | Bulk update endpoint |
| `/rest/$controller/bulkDelete` | `bulkDelete` action | Bulk delete endpoint |
| `/rest/$controller/lookupData` | `lookupData` action | Lookup data for dropdowns |
| `/proxy/$name/$url**` | `ProxyImplController` | API proxy pass-through |
| `/ping` | `XhController.ping` | Legacy health check alias |
| `404` | `XhController.notFound` | Fallback for unmatched routes |

The REST URL pattern uses HTTP method dispatch — the same URL maps to different actions based on
whether the request is `GET`, `POST`, `PUT`, or `DELETE`.

### HoistInterceptor

A Grails interceptor that matches all requests (`matchAll()`) and enforces role-based access control
before any controller action executes.

The interceptor's `before()` method:

1. **Skips WebSocket handshakes and actuator endpoints** — WebSocket requests are identified by
   both the `upgrade: websocket` header and the configured WebSocket URI path. These have their
   own security.
2. **Resolves the controller action** — Looks up the `Method` object for the requested action. If
   the method doesn't exist, throws `NotFoundException` (404).
3. **Evaluates access annotations** — Checks the method first, then the class, for one of the
   access annotations. The first annotation found is used:
   - `@AccessAll` — Any authenticated user can access.
   - `@AccessRequiresRole("ROLE")` — User must have the specified role. Takes a single `String`.
   - `@AccessRequiresAnyRole(["R1", "R2"])` — User must have at least one. Takes a `String[]`.
   - `@AccessRequiresAllRoles(["R1", "R2"])` — User must have all. Takes a `String[]`.
   - `@Access(["R1", "R2"])` — Deprecated; equivalent to `@AccessRequiresAllRoles`. Takes a `String[]`.
4. **Throws `NotAuthorizedException` (403)** — If the user lacks the required role(s).

**Every controller endpoint must have an access annotation** — either on the method or the class.
If none is found, the interceptor's behavior defaults to blocking the request (no annotation found
means no access check passed).

See [`authorization.md`](./authorization.md) for details on annotations and role management.

## Exception Handling in the Pipeline

Exceptions can be thrown at multiple stages and are handled at four levels:

1. **`BaseAuthenticationService.allowRequest()` try/catch** — Wraps the entire authentication flow.
   If authentication throws, the exception is logged but a **deliberately opaque** HTTP status code
   is returned — no JSON error body is rendered to the unverified client. This is a security measure
   to avoid leaking information before the user's identity is confirmed.
2. **`HoistFilter` catch block** — Catches any `Throwable` from the rest of the pipeline. This is
   the safety net for instance readiness failures (`ensureRunning()`), Grails internals, and any
   other unexpected errors that escape lower-level handlers.
3. **`HoistInterceptor.before()` try/catch** — The interceptor wraps its own logic in a try/catch
   block. Exceptions thrown during access checks (e.g., `NotFoundException`,
   `NotAuthorizedException`) are caught within the interceptor itself and rendered directly to the
   response via `Utils.handleException(exception: e, ..., renderTo: response)`. These exceptions
   do **not** propagate up to `HoistFilter`.
4. **`BaseController.handleException()`** — Catches exceptions thrown within controller actions
   and renders them as JSON error responses. The Grails framework calls this automatically.

Handlers 2-4 delegate to `Utils.handleException()`, which:
- Determines the appropriate HTTP status code (e.g., 401, 403, 404, 500)
- Renders a JSON error response with `{ name, message, cause, isRoutine }` structure (falsy values
  — null, false, empty strings — are filtered out, so e.g. `isRoutine` only appears when `true`)
- Logs the error (respecting `RoutineException` — expected errors log at DEBUG, not ERROR)

## Common Pitfalls

### Missing access annotations

If a controller action lacks an `@AccessRequiresRole` (or similar) annotation and the controller
class also lacks one, the request will be blocked. Always annotate either the action method or the
controller class.

```groovy
// ✅ Do: Annotate the class for a default, override on specific methods
@AccessRequiresRole('APP_USER')
class MyController extends BaseController {

    @AccessRequiresRole('APP_ADMIN')
    def adminAction() { /* restricted */ }

    def regularAction() { /* inherits APP_USER from class */ }
}

// ❌ Don't: Leave endpoints without annotations
class MyController extends BaseController {
    def someAction() { /* no annotation — will be blocked */ }
}
```

### Assuming requests reach the controller

Requests can be rejected at any stage before reaching the controller. If debugging a request that
never hits your controller code, check:

1. Is the instance running? (`ensureRunning()` rejection)
2. Is the user authenticated? (`allowRequest()` returning `false`)
3. Is the URL mapping correct? (wrong URL pattern → 404 from `UrlMappings`)
4. Does the user have the right role? (`HoistInterceptor` → 403)

### WebSocket and actuator bypass

The `HoistInterceptor` explicitly skips WebSocket upgrade requests (matched by both the
`upgrade: websocket` header and the configured WebSocket URI path) and `/actuator/` endpoints.
WebSocket security is handled separately by the WebSocket framework. Actuator endpoints should be
secured at the deployment/infrastructure level.
