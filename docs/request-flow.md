> **Status: DRAFT** — This document is awaiting review. Content may be incomplete or subject to
> change. Do not remove this banner until the document has been interactively reviewed and approved.

# Request Flow

## Overview

Every HTTP request in a Hoist application passes through a well-defined pipeline before reaching
application code. This pipeline handles Hazelcast cluster readiness, authentication, URL routing,
role-based access control, and exception handling — ensuring that by the time a controller action
executes, the user is authenticated and authorized.

Understanding this flow is essential for debugging request failures, implementing custom
authentication, and knowing where to add cross-cutting concerns.

## Source Files

| File | Location | Role |
|------|----------|------|
| `HoistCoreGrailsPlugin` | `src/main/groovy/io/xh/hoist/` | Plugin descriptor — registers filter, initializes Hazelcast |
| `HoistFilter` | `src/main/groovy/io/xh/hoist/` | Servlet filter — auth gating, cluster readiness, top-level exception catching |
| `UrlMappings` | `grails-app/controllers/io/xh/hoist/` | URL pattern routing |
| `AccessInterceptor` | `grails-app/controllers/io/xh/hoist/security/` | Grails interceptor — role annotation checks |
| `BaseController` | `grails-app/controllers/io/xh/hoist/` | Base controller — JSON rendering, exception handling |

## Architecture

### Request Pipeline

```
HTTP Request
    │
    ▼
┌─────────────────────────────┐
│  1. HoistFilter             │  Servlet filter (highest precedence)
│     • ensureRunning()       │  Verify Hazelcast cluster is ready
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
│  3. AccessInterceptor       │  Grails interceptor (matches all)
│     • Find controller method│  Resolve action to a Method
│     • Check @Access* ann.   │  Evaluate role annotations
│     • 404 if no method      │  NotFoundException for bad routes
│     • 403 if not authorized │  NotAuthorizedException for role failures
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
2. **Initializes Hazelcast** — Calls `ClusterService.initializeHazelcast()` to start the cluster.
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

1. **Cluster readiness** — `clusterService.ensureRunning()` verifies that the Hazelcast cluster is
   initialized and the instance is in a `RUNNING` state. If the cluster isn't ready, the request
   is rejected before any other processing.

2. **Authentication gating** — `authenticationService.allowRequest()` checks whether the current
   request has an authenticated user. If not, it calls `completeAuthentication()` to attempt
   authentication. If `allowRequest()` returns `false`, the request is halted (e.g., during an
   OAuth redirect). See [`authentication.md`](./authentication.md) for details.

3. **Top-level exception handling** — Any uncaught exception from the entire request pipeline
   (including Grails internals) is caught here and rendered as a JSON error response.

### UrlMappings

Defines URL patterns that route requests to controller actions:

| Pattern | Routes To | Purpose |
|---------|-----------|---------|
| `/` | `DefaultController` | Root redirect |
| `/$controller/$action?/$id?` | Standard dispatch | General controller endpoints |
| `/rest/$controller/$id?` | `POST→create, GET→read, PUT→update, DELETE→delete` | REST CRUD |
| `/rest/$controller/bulkUpdate` | `bulkUpdate` action | Bulk update endpoint |
| `/rest/$controller/bulkDelete` | `bulkDelete` action | Bulk delete endpoint |
| `/rest/$controller/lookupData` | `lookupData` action | Lookup data for dropdowns |
| `/proxy/$name/$url**` | `ProxyImplController` | API proxy pass-through |
| `/ping` | `XhController.ping` | Legacy health check alias |
| `404` | `XhController.notFound` | Fallback for unmatched routes |

The REST URL pattern uses HTTP method dispatch — the same URL maps to different actions based on
whether the request is `GET`, `POST`, `PUT`, or `DELETE`.

### AccessInterceptor

A Grails interceptor that matches all requests (`matchAll()`) and enforces role-based access control
before any controller action executes.

The interceptor's `before()` method:

1. **Skips WebSocket handshakes and actuator endpoints** — These have their own security.
2. **Resolves the controller action** — Looks up the `Method` object for the requested action. If
   the method doesn't exist, throws `NotFoundException` (404).
3. **Evaluates access annotations** — Checks the method first, then the class, for one of the
   access annotations. The first annotation found is used:
   - `@AccessAll` — Any authenticated user can access.
   - `@AccessRequiresRole("ROLE")` — User must have the specified role.
   - `@AccessRequiresAnyRole(["R1", "R2"])` — User must have at least one.
   - `@AccessRequiresAllRoles(["R1", "R2"])` — User must have all.
   - `@Access(["R1", "R2"])` — Deprecated; equivalent to `@AccessRequiresAllRoles`.
4. **Throws `NotAuthorizedException` (403)** — If the user lacks the required role(s).

**Every controller endpoint must have an access annotation** — either on the method or the class.
If none is found, the interceptor's behavior defaults to blocking the request (no annotation found
means no access check passed).

See [`authorization.md`](./authorization.md) for details on annotations and role management.

## Exception Handling in the Pipeline

Exceptions can be thrown at multiple stages and are handled at two levels:

1. **`HoistFilter` catch block** — Catches any `Throwable` from the entire pipeline. This is the
   safety net for exceptions from authentication, Grails internals, or interceptors.
2. **`BaseController.handleException()`** — Catches exceptions thrown within controller actions
   and renders them as JSON error responses. The Grails framework calls this automatically.

Both handlers delegate to `Utils.handleException()`, which:
- Determines the appropriate HTTP status code (e.g., 401, 403, 404, 500)
- Renders a JSON error response with `{ name, message }` structure
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

1. Is the cluster running? (`ensureRunning()` rejection)
2. Is the user authenticated? (`allowRequest()` returning `false`)
3. Is the URL mapping correct? (wrong URL pattern → 404 from `UrlMappings`)
4. Does the user have the right role? (`AccessInterceptor` → 403)

### WebSocket and actuator bypass

The `AccessInterceptor` explicitly skips WebSocket upgrade requests and `/actuator/` endpoints.
WebSocket security is handled separately by the WebSocket framework. Actuator endpoints should be
secured at the deployment/infrastructure level.
