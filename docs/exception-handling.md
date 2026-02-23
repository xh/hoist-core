> **Status: DRAFT** — This document is awaiting review. Content may be incomplete or subject to
> change. Do not remove this banner until the document has been interactively reviewed and approved.

# Exception Handling

## Overview

Hoist provides a structured exception hierarchy and centralized error handling pipeline that
converts server-side exceptions into meaningful, consistent JSON responses for clients. The system
serves three primary goals:

1. **Semantic HTTP status codes** — Exceptions map to appropriate HTTP status codes (401, 403, 404,
   400, 500) so that clients can distinguish authorization failures from server bugs without parsing
   error messages.
2. **Routine vs. unexpected errors** — The `RoutineException` marker interface separates expected
   business-logic errors (invalid input, missing data, insufficient permissions) from genuine bugs.
   Routine exceptions are logged at DEBUG level rather than ERROR, keeping production logs clean and
   actionable.
3. **Consistent JSON error format** — All exceptions are serialized to a standard JSON shape via
   `ThrowableSerializer`, giving the hoist-react client a reliable contract for displaying errors.

Custom exceptions exist because generic Java exceptions (`RuntimeException`,
`IllegalArgumentException`) carry no HTTP semantics and no signal about whether the error is
expected. By throwing `NotAuthorizedException` instead of `RuntimeException("forbidden")`, the
framework automatically applies the correct status code (403), logs at the right level (DEBUG), and
tells the client this is a routine condition — all without any per-endpoint plumbing.

## Source Files

| File | Location | Role |
|------|----------|------|
| `ExceptionHandler` | `src/main/groovy/io/xh/hoist/exception/` | Central exception processing — logging, HTTP status, JSON rendering |
| `HttpException` | `src/main/groovy/io/xh/hoist/exception/` | Base class for exceptions with an HTTP status code |
| `RoutineException` | `src/main/groovy/io/xh/hoist/exception/` | Marker interface — expected errors, logged at DEBUG |
| `RoutineRuntimeException` | `src/main/groovy/io/xh/hoist/exception/` | Concrete `RuntimeException` implementing `RoutineException` |
| `NotAuthorizedException` | `src/main/groovy/io/xh/hoist/exception/` | 403 Forbidden — user lacks required role |
| `NotAuthenticatedException` | `src/main/groovy/io/xh/hoist/exception/` | 401 Unauthorized — user is not authenticated |
| `NotFoundException` | `src/main/groovy/io/xh/hoist/exception/` | 404 Not Found — unknown URL or resource |
| `ValidationException` | `src/main/groovy/io/xh/hoist/exception/` | Wraps GORM `ValidationException` with human-readable messages |
| `DataNotAvailableException` | `src/main/groovy/io/xh/hoist/exception/` | Data temporarily unavailable (e.g. startup, new business day) |
| `InstanceNotAvailableException` | `src/main/groovy/io/xh/hoist/exception/` | Server instance not yet ready for requests |
| `InstanceNotFoundException` | `src/main/groovy/io/xh/hoist/exception/` | Requested cluster instance does not exist |
| `SessionMismatchException` | `src/main/groovy/io/xh/hoist/exception/` | Client username does not match session user |
| `ExternalHttpException` | `src/main/groovy/io/xh/hoist/exception/` | HTTP call to an external service failed |
| `ClusterExecutionException` | `src/main/groovy/io/xh/hoist/exception/` | Serialization-safe wrapper for remote cluster task failures |
| `ClusterTaskException` | `src/main/groovy/io/xh/hoist/cluster/` | DTO for transferring exception data across cluster nodes |
| `ThrowableSerializer` | `src/main/groovy/io/xh/hoist/json/serializer/` | Jackson serializer — converts exceptions to JSON |
| `BaseController` | `grails-app/controllers/io/xh/hoist/` | Controller base class — catches unhandled exceptions |
| `HoistFilter` | `src/main/groovy/io/xh/hoist/` | Servlet filter — catches exceptions from auth/cluster checks |
| `AccessInterceptor` | `grails-app/controllers/io/xh/hoist/security/` | Grails interceptor — throws `NotAuthorizedException`/`NotFoundException` |
| `Utils` | `src/main/groovy/io/xh/hoist/util/` | Static `handleException()` entry point |

## Key Classes

### Exception Hierarchy

```
Throwable
├── RuntimeException
│   ├── HttpException                        ← has statusCode property
│   │   ├── NotAuthorizedException           ← 403, implements RoutineException
│   │   ├── NotAuthenticatedException        ← 401, implements RoutineException
│   │   ├── NotFoundException                ← 404
│   │   └── ExternalHttpException            ← status from remote server
│   │
│   ├── RoutineRuntimeException              ← implements RoutineException
│   │   ├── DataNotAvailableException        ← data temporarily unavailable
│   │   ├── InstanceNotAvailableException    ← server not ready
│   │   └── InstanceNotFoundException        ← cluster instance not found
│   │
│   ├── ValidationException                  ← implements RoutineException (wraps GORM errors)
│   ├── SessionMismatchException             ← implements RoutineException
│   └── ClusterTaskException                 ← DTO for cross-cluster exception transfer
│
├── Exception
│   └── ClusterExecutionException            ← Kryo-safe wrapper for remote failures
│
└── (any other Throwable)                    ← caught and rendered as 500
```

### `RoutineException` (marker interface)

`RoutineException` is a Java interface (not a class) that marks exceptions representing expected
application conditions. It carries no methods or fields — its only purpose is to signal the
framework's exception handling pipeline:

- **Logging**: `ExceptionHandler.shouldLogDebug()` returns `true` for `RoutineException` instances,
  causing them to be logged at DEBUG level instead of ERROR. This prevents expected business errors
  (e.g. "user doesn't have ADMIN role") from triggering error monitoring.
- **HTTP status**: When a `RoutineException` is not also an `HttpException`, it maps to
  `400 Bad Request` rather than `500 Internal Server Error`.
- **Client display**: The `isRoutine` flag is included in the serialized JSON, allowing hoist-react
  to display the error as a user-facing message rather than an "unexpected error" dialog.

Classes implementing `RoutineException`: `RoutineRuntimeException`, `NotAuthorizedException`,
`NotAuthenticatedException`, `ValidationException`, `SessionMismatchException`,
`DataNotAvailableException`, `InstanceNotAvailableException`, `InstanceNotFoundException`.

### `HttpException`

Base class for exceptions that carry an explicit HTTP status code. Constructed with a message,
optional cause, and an integer `statusCode`:

```groovy
class HttpException extends RuntimeException {
    Integer statusCode

    HttpException(String msg, Throwable cause, Integer statusCode) {
        super(msg, cause)
        this.statusCode = statusCode
    }
}
```

The `ExceptionHandler.getHttpStatus()` method reads `statusCode` directly from `HttpException`
instances (with one exception — `ExternalHttpException`, discussed below).

### `HttpException` Subclasses

| Class | Status Code | Implements `RoutineException`? | Typical Usage |
|-------|-------------|-------------------------------|---------------|
| `NotAuthenticatedException` | 401 | Yes | Thrown by `BaseAuthenticationService` when a request cannot be authenticated |
| `NotAuthorizedException` | 403 | Yes | Thrown by `AccessInterceptor` when user lacks required roles, or by app code for authorization failures |
| `NotFoundException` | 404 | No | Thrown by `AccessInterceptor` when no controller method matches, or by app code for missing resources |
| `ExternalHttpException` | varies | No | Wraps failures from HTTP calls to external services; carries the remote status code but is **not** used for the response status (see below) |

**`ExternalHttpException` — the deliberate exception.** When `ExceptionHandler.getHttpStatus()`
encounters an `ExternalHttpException`, it intentionally ignores the `statusCode` property and falls
through to the default logic (returning 500). This prevents a downstream service's 401 or 403 from
being forwarded as the Hoist server's own response status.

### `RoutineRuntimeException` and Subclasses

`RoutineRuntimeException` is a concrete `RuntimeException` implementing `RoutineException`. It
serves as the general-purpose "expected error" class and as the base class for more specific routine
exceptions:

| Class | Default Message | Purpose |
|-------|-----------------|---------|
| `RoutineRuntimeException` | *(caller-provided)* | General expected error — logged at DEBUG, sent as 400 |
| `DataNotAvailableException` | `"Data not available"` | Requested data is temporarily unavailable (startup, new business day) |
| `InstanceNotAvailableException` | *(caller-provided)* | Server instance is not yet ready for requests |
| `InstanceNotFoundException` | *(caller-provided)* | Named cluster instance does not exist |

### `ValidationException`

Wraps Grails' `grails.validation.ValidationException` to extract human-readable error messages
from GORM validation errors. The `ExceptionHandler.preprocess()` method automatically converts
incoming Grails `ValidationException` instances into Hoist `ValidationException` instances:

```groovy
// In ExceptionHandler.preprocess():
if (t instanceof grails.validation.ValidationException) {
    t = new ValidationException(t)
}
```

This means application code does not need to catch or wrap GORM validation exceptions explicitly —
they are transformed automatically during exception handling.

### `ExceptionHandler`

The central exception processing class, installed as a Spring bean (`xhExceptionHandler`). It
provides three capabilities:

1. **`handleException()`** — Preprocesses, logs, and optionally renders an exception to an HTTP
   response. This is the method called by `BaseController`, `HoistFilter`, `AccessInterceptor`,
   and `Timer`.

2. **`getHttpStatus()`** — Determines the HTTP status code for an exception.

3. **`summaryTextForThrowable()`** — Produces a one-line summary string (e.g.
   `"Not Authorized [NotAuthorizedException]"`) used by Timer admin stats.

**Customization**: `ExceptionHandler` is designed to be overridden. Applications can replace it by
defining an alternative Spring bean in `resources.groovy`. The `preprocess()` and
`shouldLogDebug()` methods are `protected` template methods intended for override.

### `ThrowableSerializer`

A Jackson `StdSerializer<Throwable>` that controls the JSON shape of all exceptions. Registered
automatically by `JSONSerializer`'s static initializer.

If the exception implements `JSONFormat`, the serializer delegates to `formatForJSON()`.
Otherwise, it produces a standard map:

```groovy
[
    name     : t.class.simpleName,
    message  : t.message,
    cause    : t.cause?.message,
    isRoutine: t instanceof RoutineException
]
```

Entries with falsy values — `null`, `false`, empty strings, and `0` — are stripped (via
`.findAll { it.value }`).

## HTTP Status Mapping

The `ExceptionHandler.getHttpStatus()` method applies these rules in order:

| Condition | HTTP Status |
|-----------|-------------|
| Exception is `HttpException` (but **not** `ExternalHttpException`) | Use exception's `statusCode` property |
| Exception implements `RoutineException` | `400 Bad Request` |
| All other exceptions | `500 Internal Server Error` |

Concrete mappings for the built-in exception types:

| Exception Class | HTTP Status | Log Level |
|-----------------|-------------|-----------|
| `NotAuthenticatedException` | 401 Unauthorized | DEBUG |
| `NotAuthorizedException` | 403 Forbidden | DEBUG |
| `NotFoundException` | 404 Not Found | ERROR |
| `RoutineRuntimeException` | 400 Bad Request | DEBUG |
| `DataNotAvailableException` | 400 Bad Request | DEBUG |
| `InstanceNotAvailableException` | 400 Bad Request | DEBUG |
| `InstanceNotFoundException` | 400 Bad Request | DEBUG |
| `ValidationException` | 400 Bad Request | DEBUG |
| `SessionMismatchException` | 400 Bad Request | DEBUG |
| `ExternalHttpException` | 500 Internal Server Error | ERROR |
| Any other `RuntimeException` | 500 Internal Server Error | ERROR |

Note that `NotFoundException` does **not** implement `RoutineException`, so it logs at ERROR level.
This is intentional — 404s hitting the server typically indicate a client bug or misconfiguration
that warrants investigation.

## JSON Error Format

When an exception is rendered to an HTTP response, the body is a JSON object with this shape:

```json
{
    "name": "NotAuthorizedException",
    "message": "You do not have the required role(s) for this action.",
    "isRoutine": true
}
```

| Field | Type | Present When | Description |
|-------|------|-------------|-------------|
| `name` | string | Always | Exception class simple name (e.g. `"NotAuthorizedException"`) |
| `message` | string | When non-null | The exception's message |
| `cause` | string | When exception has a cause | The cause exception's message |
| `isRoutine` | boolean | When `true` | Indicates a `RoutineException` — client should display as a user message, not a bug report |

Fields with `null` values are omitted from the response. The `isRoutine` field is only present
when `true` — if absent, the client should treat the error as unexpected.

The HTTP `Content-Type` header is always set to `application/json`.

## Exception Handling Pipeline

### Where Exceptions Are Caught

Hoist catches exceptions at three layers, ensuring that no unhandled exception escapes without
a proper JSON error response:

```
HTTP Request
    │
    ├── HoistFilter.doFilter()                ← catches auth/cluster exceptions
    │       │
    │       └── AccessInterceptor.before()    ← catches role/route check exceptions
    │               │
    │               └── BaseController        ← catches controller action exceptions
    │                       │
    │                       └── runAsync()    ← catches async action exceptions
```

**`HoistFilter`** — Wraps the entire filter chain in a try/catch. If `clusterService.ensureRunning()`
or `authenticationService.allowRequest()` throws, the exception is caught here. This handles
`InstanceNotAvailableException` (cluster not ready) and `NotAuthenticatedException`.

**`AccessInterceptor`** — Checks controller access annotations (`@AccessRequiresRole`, etc.)
before the controller action executes. Throws `NotFoundException` if no matching controller method
is found, or `NotAuthorizedException` if the user lacks the required role(s). Catches its own
exceptions and delegates to `Utils.handleException()`.

**`BaseController`** — The `handleException(Exception)` method (a Grails convention) catches any
exception thrown during a controller action. The `runAsync()` wrapper catches exceptions from
asynchronous actions.

All three layers delegate to the same pipeline:

```groovy
Utils.handleException(
    exception: t,
    renderTo: response,      // HttpServletResponse — omitted in Timer context
    logTo: this,             // LogSupport — determines the logger name
    logMessage: [...]        // Optional context for the log entry
)
```

### Processing Steps

`ExceptionHandler.handleException()` performs these steps:

1. **Preprocess** — `preprocess(t)` converts Grails `ValidationException` to Hoist
   `ValidationException`, then sanitizes the stack trace via `GrailsUtil.deepSanitize()`.

2. **Log** — If a `LogSupport` target is provided, logs the exception at the appropriate level:
   - DEBUG if `shouldLogDebug(t)` returns `true` (i.e. `RoutineException` instances)
   - ERROR otherwise

3. **Render** — If an `HttpServletResponse` is provided:
   - Sets the HTTP status code via `getHttpStatus(t)`
   - Sets `Content-Type: application/json`
   - Writes the exception as JSON via `JSONSerializer.serialize(t)` (which uses
     `ThrowableSerializer`)
   - Flushes the response buffer

### Timer Exception Handling

`Timer` (the Hoist polling timer) also uses `Utils.handleException()`, but without a `renderTo`
response — exceptions in timers are logged only, not rendered to HTTP:

```groovy
Utils.handleException(
    exception: throwable,
    logTo: this,
    logMessage: "Failure in '$name'"
)
```

Timer additionally captures a one-line error summary via
`ExceptionHandler.summaryTextForThrowable()` for display in admin stats.

## Common Patterns

### Throwing Routine Errors for User-Facing Messages

Use `RoutineRuntimeException` when the error is expected and the message should be shown to the
user. The framework logs at DEBUG and returns 400:

```groovy
// ✅ Do: Use RoutineRuntimeException for expected validation/business logic errors
if (!portfolio) {
    throw new RoutineRuntimeException('Please select a portfolio before running this report.')
}
```

### Throwing HTTP Exceptions for Authorization

```groovy
// ✅ Do: Use NotAuthorizedException when the user lacks permission for a specific resource
if (!blob.isOwnedBy(username)) {
    throw new NotAuthorizedException(
        "User '$username' does not have access to JsonBlob with token '${blob.token}'"
    )
}
```

### Throwing NotFoundException for Missing Resources

```groovy
// ✅ Do: Use NotFoundException for resources that should exist but don't
def config = AppConfig.findByName(name)
if (!config) {
    throw new NotFoundException("Config not found: $name")
}
```

### Signaling Temporarily Unavailable Data

```groovy
// ✅ Do: Use DataNotAvailableException when data will become available later
void getMarketData() {
    if (!marketDataLoaded) {
        throw new DataNotAvailableException('Market data is still loading. Please try again shortly.')
    }
    // ... return data
}
```

### Letting GORM Validation Exceptions Propagate

GORM validation exceptions are automatically converted by `ExceptionHandler.preprocess()`. You do
not need to catch and wrap them:

```groovy
// ✅ Do: Let GORM validation exceptions propagate naturally
def config = new AppConfig(name: name, valueType: valueType)
config.save(failOnError: true)
// If validation fails, Grails throws grails.validation.ValidationException,
// which ExceptionHandler converts to io.xh.hoist.exception.ValidationException
// with a human-readable message.

// ❌ Don't: Manually catch and re-wrap GORM validation exceptions
try {
    config.save(failOnError: true)
} catch (grails.validation.ValidationException e) {
    throw new RuntimeException(e.errors.toString())  // Loses the RoutineException semantics
}
```

### Guarding Long-Running Operations

```groovy
// ✅ Do: Use RoutineRuntimeException for timeout/limit conditions the user can act on
if (System.currentTimeMillis() - startTime > MAX_QUERY_TIME) {
    throw new RoutineRuntimeException('Query took too long. Log search aborted.')
}
```

### Overriding ExceptionHandler

Applications can customize exception handling by providing an alternative Spring bean:

```groovy
// grails-app/conf/spring/resources.groovy
beans = {
    xhExceptionHandler(MyCustomExceptionHandler)
}
```

```groovy
// src/main/groovy/com/myapp/MyCustomExceptionHandler.groovy
class MyCustomExceptionHandler extends ExceptionHandler {

    @Override
    protected boolean shouldLogDebug(Throwable t) {
        // Also suppress logging for a third-party exception type
        return super.shouldLogDebug(t) || t instanceof ThirdPartyTimeoutException
    }

    @Override
    protected Throwable preprocess(Throwable t) {
        // Convert a third-party exception to a routine exception
        if (t instanceof ThirdPartyTimeoutException) {
            t = new RoutineRuntimeException(t.message)
        }
        return super.preprocess(t)
    }
}
```

## Client Integration

The hoist-react client receives exception responses as JSON objects with the shape described in
[JSON Error Format](#json-error-format). The key field for client-side behavior is `isRoutine`:

- **`isRoutine: true`** — The client displays the `message` as a user-facing notification or
  inline message. These are expected conditions (e.g. validation failure, insufficient permissions)
  and the user can usually take action to resolve them.

- **`isRoutine` absent/false** — The client treats the error as unexpected and may display a more
  prominent error dialog, offer to send an error report, or log the error via activity tracking.

The HTTP status code also drives client behavior:

| Status | Client Behavior |
|--------|----------------|
| 401 | Triggers re-authentication flow |
| 403 | Displays "access denied" messaging |
| 404 | Typically indicates a client-side routing bug |
| 400 | Displays the error message to the user |
| 500 | Displays an "unexpected error" notification |

### Cluster Exception Transfer

When controller actions are forwarded to other cluster instances (via `ClusterService`),
exceptions must cross process boundaries. `ClusterTaskException` pre-serializes the original
exception to JSON and captures its HTTP status code. `BaseController.renderClusterJSON()` then
renders the pre-serialized JSON and status code directly, preserving the original exception's
characteristics for the client.

## Common Pitfalls

### Throwing generic RuntimeException instead of a Hoist exception

Generic exceptions are logged at ERROR level and return a 500 status code. If the error is
expected (e.g. bad user input), use the appropriate Hoist exception type:

```groovy
// ❌ Don't: Use generic RuntimeException for expected errors
if (name.isBlank()) {
    throw new RuntimeException('Name is required')  // Logs at ERROR, returns 500
}

// ✅ Do: Use RoutineRuntimeException for expected errors
if (name.isBlank()) {
    throw new RoutineRuntimeException('Name is required')  // Logs at DEBUG, returns 400
}
```

Using `RuntimeException` for expected errors pollutes ERROR logs with noise, making it harder to
spot genuine bugs. It also causes the client to display an "unexpected error" dialog instead of a
helpful message.

### Catching exceptions too broadly in controller actions

`BaseController` already catches all exceptions and routes them through `ExceptionHandler`. Adding
your own try/catch around entire actions defeats this pipeline:

```groovy
// ❌ Don't: Wrap entire action in try/catch
def myAction() {
    try {
        def result = myService.doWork()
        renderJSON(result)
    } catch (Exception e) {
        log.error('Error in myAction', e)
        render(status: 500, text: 'Something went wrong')
    }
}

// ✅ Do: Let exceptions propagate to BaseController's handler
def myAction() {
    def result = myService.doWork()
    renderJSON(result)
}
```

The manual catch loses the `RoutineException` semantics, bypasses `ThrowableSerializer` (so the
client gets an unexpected response format), and double-logs errors since `BaseController` never
sees the exception.

### Forwarding external HTTP status codes to the client

When an HTTP call to an external service fails, do not re-throw it as a plain `HttpException` with
the remote status code. This is exactly why `ExternalHttpException` exists — its status code is
intentionally ignored by `getHttpStatus()`:

```groovy
// ❌ Don't: Forward an external 401 as your server's response
catch (ExternalHttpException e) {
    throw new HttpException(e.message, e, e.statusCode)  // Client thinks *your* server returned 401
}

// ✅ Do: Let ExternalHttpException propagate, or wrap in RoutineRuntimeException with context
catch (ExternalHttpException e) {
    throw new RoutineRuntimeException("Data feed unavailable: ${e.message}")
}
```

### Swallowing exceptions in service code without logging

If you catch an exception in service code to handle it gracefully, still log it — otherwise the
failure is invisible:

```groovy
// ❌ Don't: Silently swallow exceptions
try {
    refreshCache()
} catch (Exception e) {
    // silently ignored — if this keeps failing, nobody will know
}

// ✅ Do: Log the exception even if you handle it gracefully
try {
    refreshCache()
} catch (Exception e) {
    logWarn('Cache refresh failed, using stale data', e)
}
```

### Assuming NotFoundException is a RoutineException

`NotFoundException` does **not** implement `RoutineException`. It is logged at ERROR and returns
404. This is by design — a 404 hitting the server usually indicates a client bug (bad URL) rather
than an expected user condition. If you need a routine "not found" response, use
`RoutineRuntimeException` with an appropriate message:

```groovy
// This logs at ERROR — appropriate for unexpected missing routes
throw new NotFoundException()

// This logs at DEBUG — appropriate for user-searchable resources that may not exist
throw new RoutineRuntimeException("No results found for query: $query")
```
