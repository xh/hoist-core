# Hoist Core v36 Upgrade Notes

> **From:** v35.x -> v36.1.0 | **Released:** 2026-01-27 | **Difficulty:** 🟢 LOW, excepting multi-instance apps w/websockets

## Overview

Hoist Core v36 introduces cluster-aware WebSocket support, new security annotations to replace the
deprecated `@Access` annotation, and a minor Grails patch bump. The v36.1.0 follow-up adds
optional Spring Boot Actuator support and optimized client initialization.

The most significant app-level impacts are:

- **`@Access` annotation deprecated** -- all usages should be migrated to the new
  `@AccessRequiresRole`, `@AccessRequiresAllRoles`, or `@AccessRequiresAnyRole` annotations.
  This is a straightforward find-and-replace for most apps.
- **WebSocket API changes** -- `WebSocketService` is now cluster-aware. Apps using websockets in a
  multi-instance deployment must review their usage of `getAllChannels()` and related methods to
  avoid message duplication and account for the new return types.

## Upgrade Steps

### 1. Update `gradle.properties`

Bump the hoist-core version and optionally align Grails.

**File:** `gradle.properties`

Before:
```properties
hoistCoreVersion=35.x.x
```

After:
```properties
hoistCoreVersion=36.1.0
```

We also recommend updating your `grailsVersion` to `7.0.5` to stay aligned with hoist-core's
tested version:

```properties
grailsVersion=7.0.5
```

No other property changes are required.

### 2. Migrate `@Access` annotations

The `@Access` annotation has been deprecated in favor of three new, more descriptive annotations.
`@Access` will continue to function in v36 but will be removed in a future release. We recommend
migrating now.

**Find affected files:**
```bash
grep -rn "@Access(" grails-app/controllers/ src/
```

#### Single-role usage (most common)

The vast majority of `@Access` usages check a single role. These should become
`@AccessRequiresRole`:

Before:
```groovy
import io.xh.hoist.security.Access

@Access(['HOIST_ADMIN'])
class MyAdminController extends BaseController {

    @Access(['MY_SPECIAL_ROLE'])
    def sensitiveAction() { ... }
}
```

After:
```groovy
import io.xh.hoist.security.AccessRequiresRole

@AccessRequiresRole('HOIST_ADMIN')
class MyAdminController extends BaseController {

    @AccessRequiresRole('MY_SPECIAL_ROLE')
    def sensitiveAction() { ... }
}
```

Note the syntax change: `@Access(['ROLE'])` (array with one element) becomes
`@AccessRequiresRole('ROLE')` (single string). The new annotation takes a `String`, not a
`String[]`.

#### Multi-role usage (all required)

If `@Access` was used with multiple roles (which required **all** of them), use
`@AccessRequiresAllRoles`:

Before:
```groovy
@Access(['READ_DATA', 'MANAGE_DATA'])
```

After:
```groovy
@AccessRequiresAllRoles(['READ_DATA', 'MANAGE_DATA'])
```

This preserves the same semantics — the user must have every listed role.

#### Multi-role usage (any required — new capability)

v36 introduces `@AccessRequiresAnyRole` for cases where a user needs **at least one** of the
listed roles. This was not possible with `@Access` and required runtime checks. If you have
controller actions guarded by runtime `hasRole()` checks that could be replaced with a declarative
annotation, consider using this:

```groovy
@AccessRequiresAnyRole(['EDIT_RECORDS', 'APPROVE_RECORDS'])
```

#### Update imports

After replacing annotations, update your imports. Search for any remaining references:

```bash
grep -rn "import io.xh.hoist.security.Access$" grails-app/controllers/ src/
```

Replace with the appropriate import(s):
```groovy
import io.xh.hoist.security.AccessRequiresRole
import io.xh.hoist.security.AccessRequiresAllRoles   // if needed
import io.xh.hoist.security.AccessRequiresAnyRole    // if needed
```

Note: `@AccessAll` is **unchanged** and requires no migration.

### 3. Update WebSocket usage (multi-instance apps only)

If your app does **not** use websockets or runs as a single instance, skip this step.

`WebSocketService` has been upgraded to be fully cluster-aware. The key API changes are:

| Method | Before (v35) | After (v36) |
|--------|-------------|-------------|
| `getAllChannels()` | Returns `Collection<HoistWebSocketChannel>` (local only) | Returns `Collection<Map>` (all instances) |
| `getLocalChannels()` | N/A | Returns `Collection<HoistWebSocketChannel>` (local only) |
| `pushToChannel()` | Local only — caller must route to correct instance | Cluster-aware — routes automatically |
| `pushToChannels()` | Local only | Cluster-aware |
| `pushToAllChannels()` | N/A | Pushes to all channels across all instances |
| `pushToLocalChannels()` | N/A | Pushes to all channels on local instance only |
| `hasChannel()` | Checks local only | Checks all instances |
| `hasLocalChannel()` | N/A | Checks local instance only |

`hasChannel()` now checks cluster-wide, which is typically the desired behavior (e.g. when culling
stale subscriptions). Use `hasLocalChannel()` only if you specifically need to know whether a
channel is connected to *this* instance.

**Find affected files:**
```bash
grep -rn "allChannels\|getAllChannels\|webSocketService" grails-app/ src/
```

#### 3a. Replace broadcast-to-all patterns with `pushToAllChannels()`

The most common pattern to update is broadcasting a message to all connected clients. If your code
collected all channel keys and pushed to each, replace with the new convenience method:

Before:
```groovy
webSocketService.pushToChannels(
    webSocketService.allChannels*.key,
    'myTopic',
    myData
)
```

After:
```groovy
webSocketService.pushToAllChannels('myTopic', myData)
```

**Choosing between `pushToAllChannels()` and `pushToLocalChannels()`:** Use `pushToLocalChannels()`
when the triggering event already fires on every instance (e.g. a replicated cache `onChange`
listener or a cluster-wide topic subscription). Use `pushToAllChannels()` when the push originates
from a single instance (e.g. a `primaryOnly` timer callback or a user-initiated action).

Before:
```groovy
// In a listener that fires on every instance (e.g. replicated cache onChange)
webSocketService.pushToChannels(
    webSocketService.allChannels*.key,
    'dataRefresh',
    refreshData
)
```

After:
```groovy
// pushToLocalChannels sends only to clients connected to THIS instance,
// avoiding duplicates when the listener runs on every instance
webSocketService.pushToLocalChannels('dataRefresh', refreshData)
```

If you call `pushToChannel()` or `pushToChannels()` with specific channel keys (e.g. keys stored
from a client registration call), no code changes are needed — these methods now route to the
correct instance automatically.

#### 3b. Update code that accesses channel properties

`getAllChannels()` now returns `Collection<Map>` (serialized channel metadata from all instances)
rather than `Collection<HoistWebSocketChannel>` (local objects with full session access). If your
code iterates `allChannels` and accesses properties like `.user`, `.authUsername`, or
`.apparentUsername`, you must adapt.

The Maps returned by `getAllChannels()` contain the following keys (matching the fields from
`HoistWebSocketChannel.formatForJSON()`):

```
key, authUser, apparentUser, isOpen, createdTime, sentMessageCount,
lastSentTime, receivedMessageCount, lastReceivedTime, appVersion,
appBuild, clientAppCode, instance, loadId, tabId
```

Note that `authUser` and `apparentUser` are serialized as username strings in the Map form, not
`HoistUser` objects. If you need to check roles, look up the user via `userService.find()`:

Before:
```groovy
webSocketService.pushToChannels(
    webSocketService.allChannels
        .findAll { it.user.hasRole('MY_ROLE') }
        .collect { it.key },
    'myTopic',
    myData
)
```

After:
```groovy
webSocketService.pushToChannels(
    webSocketService.allChannels
        .findAll {
            def user = userService.find(it.apparentUser as String)
            user?.hasRole('MY_ROLE')
        }
        .collect { it.key as String },
    'myTopic',
    myData
)
```

If you only need local channels and want to continue working with `HoistWebSocketChannel` objects,
use `getLocalChannels()` instead — it returns `Collection<HoistWebSocketChannel>` as before.

### 4. Optional: Enable Spring Boot Actuator endpoints (v36.1.0)

v36.1.0 enables underlying support for Spring Boot Actuator. To expose health, info, or metrics
endpoints, add configuration to your `application.groovy`:

```groovy
management.endpoints.web.exposure.include = 'health,info,metrics'
```

See the [Spring Boot Actuator docs](https://docs.spring.io/spring-boot/3.5/reference/actuator/endpoints.html)
for the full list of available endpoints and configuration options.

Note: Actuator endpoints bypass Hoist's role-based authorization (`AccessInterceptor`) but still
pass through the authentication filter (`HoistFilter`). The level of protection depends on your
`BaseAuthenticationService` implementation. Use Spring Security or network-level controls for
additional restrictions in production.

## Verification Checklist

After completing all steps:

- [ ] `./gradlew compileGroovy` succeeds
- [ ] Application starts without errors
- [ ] Admin Console loads and is functional
- [ ] Authentication works (login/logout)
- [ ] No deprecated `@Access` annotations remain: `grep -rn "@Access(" grails-app/controllers/ src/`
- [ ] WebSocket connections establish and receive messages (if applicable)
- [ ] Multi-instance WebSocket push delivers to clients on all instances (if applicable)

## Reference

- [Toolbox on GitHub](https://github.com/xh/toolbox) -- canonical example of a Hoist app
