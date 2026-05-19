---
status: active
type: implementation-plan
description: Plan to make hoist-core identity/request access safe across async boundaries (post-recycle threads, auto-instrumented spans).
created: 2026-05-19
---

# Plan: Make hoist-core identity/request access safe across async boundaries

## Background

`RequestFacade.checkFacade()` throws `IllegalStateException` once Tomcat recycles the
underlying request. Hoist-core hits this when async or post-response code paths dereference
the live request indirectly — currently observed via `TraceService.createSpan` →
`hoistTags()` → `IdentityService.getAuthUsername` → `request.getSession()`. The same shape
applies to `TagSpanProcessor.onStart` (every auto-instrumented span) and
`TrackService.parseSubmittedEntry` (userAgent/browser/device).

Root cause: framework accessors re-read identity from the session on every call, and
`WebPromises.task` propagates a live `GrailsWebRequest` reference into worker threads that
outlive the original request.

## Guiding principle

The session is the durable source of truth for identity, but it is read at session-resume
points only — i.e., when a thread starts processing on behalf of a user. After that, identity
is fixed for the thread and should live in thread-local state. No accessor should hit the
session on every call; nothing outside the request thread should touch a `RequestFacade` at
all.

---

## Phase 1 — Thread-scoped identity cache + defensive guards (minor release)

**Goal:** Make identity a thread property sourced from the session once at thread entry,
eliminate per-call session reads, and add defensive guards on the remaining request-touching
paths.

### Changes

1. **Introduce `HoistIdentity` — single immutable POGO holding `username` and `authUsername`.**
   Lives in `io.xh.hoist.user`. Constructed once at thread entry; never mutated. All identity
   reads return fields off this object.

2. **`IdentityService` uses a single `ThreadLocal<HoistIdentity>` as primary identity source.**
   Replaces the existing `threadUsername` / `threadAuthUsername` ThreadLocals (the legacy pair
   may be kept populated for one release for BC, or removed if no external callers exist).
   All public accessors (`getUsername`, `getAuthUsername`, `getUser`, `getAuthUser`,
   `findHoistUsername`) read from the cache. Session is no longer touched from accessors.

3. **Cache populated at request entry.**
   `HoistFilter` (post-auth) reads session attributes once, constructs a `HoistIdentity`,
   installs it on the ThreadLocal, clears in `finally` when the filter exits. One session
   read per request, on the request thread, where the facade is live.

4. **Identity-mutating operations replace the cached `HoistIdentity` in lock-step with the session.**
   `login()`, `logout()`, `impersonate()`, `endImpersonate()` already write to the session —
   they additionally construct and install a fresh `HoistIdentity` on the ThreadLocal.
   Finite, audit-able set of mutation sites.

5. **New `IdentityPropagatingPromiseFactory` (modeled on `ContextPropagatingPromiseFactory`).**
   At task creation: capture the originating thread's `HoistIdentity`. On the worker:
   install it on the ThreadLocal before the closure runs; clear in `finally`. Installed at
   startup, composed with the OTel context-propagating factory.

6. **`ClusterTask` constructs and installs a `HoistIdentity`** rather than setting the raw
   ThreadLocals. Same behavior; unified accessor surface.

7. **Defensive guards** on remaining request-touching paths, for any code path that bypasses
   the identity cache:
   - `IdentityService.getSessionIfExists` — wrap `request?.getSession(false)` in
     `try/catch (IllegalStateException) → null`. A recycled facade is semantically equivalent
     to "no session," which already has correct fallback behavior.
   - `TrackService.parseSubmittedEntry` — wrap `currentRequest?.getHeader(...)`,
     `getBrowser(currentRequest)`, `getDevice(currentRequest)` in the same guard. These
     fields are best-effort observability; null is acceptable.

### Effect

- `TraceService.hoistTags()`, `TagSpanProcessor.onStart`, and any other identity consumer no
  longer touch a request or session. The observed bug is gone.
- Async work spawned via `task {}` (web or plain) receives the originating user's identity
  automatically via the decorator.
- Any future or app-side code that bypasses the cache and hits the recycled facade fails
  cleanly (null fallback) rather than throwing.

### Risk

Low. Identity accessors keep their signatures and return values. The behavioral shift is
"session is read once at request start instead of N times during the request," which is a
perf improvement, not a semantic change. The defensive guards are pure null-where-it-already-
fails-null.

### Ships as

Minor release.

---

## Phase 2 — *Optional* — Eliminate live-request propagation into worker threads

**Goal:** Defense-in-depth. After Phase 1 the observed bug is fixed, but any future or
app-side code that calls `Utils.currentRequest`/`request.X` inside an async block still
holds a live-but-doomed facade. Phase 2 closes that door.

**Status:** Optional. Evaluate after Phase 1 ships and we see whether real-world reports
surface code paths Phase 1 doesn't already cover.

### Why it's separate

`BaseController.runAsync` is built on `WebPromises.task`, which propagates
`RequestContextHolder` + GORM session binding to the worker. That's exactly what
`WebPromises.task` is designed for, and it's plausibly used by downstream apps for **async
response rendering** (start work on a worker, eventually `render`/`renderJSON` to the
still-open response). Switching the factory under existing callers would break that use
case. The current `runAsync` exception handler itself reads `actionName` (a `webRequest`
lookup), so even the framework's own code assumes web propagation.

### Recommended shape: add a sibling, do not change `runAsync`

1. **New `BaseController.runDetached(Closure)`.**
   Built on plain `Promises.task` + the identity propagation decorator from Phase 1. On
   the worker:
   - Identity is available via `identityService.authUsername` (from decorator).
   - `RequestContextHolder.resetRequestAttributes()` is called so `Utils.currentRequest`
     returns null — no live facade reference on the worker.
   - Exception handler snapshots needed controller state (`actionName`) on the request
     thread into the closure.

2. **Documentation:**
   - `runAsync` — "Use for async response handling. Worker thread has access to
     request/response. Do not use for work that outlives the response."
   - `runDetached` — "Use for fire-and-forget background work that outlives the response.
     Identity propagates; request/response are not accessible."

3. **Optional deprecation pass.** If telemetry or code inspection shows that `runAsync`
   is overwhelmingly used for fire-and-forget rather than async response handling,
   consider deprecating `runAsync` in favor of explicit `runAsync` / `runDetached`
   naming in a future major.

### Risk if pursued

Low — additive API. The risk we avoided is the one of changing `runAsync` semantics, which
this approach explicitly sidesteps.

### Ships as

Separate minor release. Only if real-world need emerges.

---

## What this plan deliberately does *not* do

- Does **not** introduce a multi-field request snapshot POGO. Identity is the only state
  that needs to survive thread transitions; non-identity request fields are either
  observability best-effort (handled by the Phase 1 defensive guards) or genuinely scoped
  to the request thread.
- Does **not** change `TraceService.hoistTags` or `TagSpanProcessor` directly. They benefit
  automatically once identity comes from cache.
- Does **not** change `WebPromises.task` propagation semantics. Phase 2 (if pursued) adds
  a parallel API rather than mutating the existing one.
- Does **not** deprecate any current public API. The Phase 1 ThreadLocals may be unified
  internally but the existing ones can stay populated for BC if needed.

---

## Verification

For Phase 1:

- Unit: `IdentityService` accessors read from the thread cache, populate from session only
  at filter entry, update on login/logout/impersonate.
- Unit: `IdentityPropagatingPromiseFactory` captures on task creation, installs on worker,
  clears in finally, handles nesting.
- Integration: a controller that does
  `runAsync { Thread.sleep(200); identityService.authUsername }` returns the correct
  username with no `IllegalStateException` after the response is rendered.
- Integration: same scenario hitting `trackService.track(...)` and opening a manual span —
  confirms tags resolve, no exception.
- Regression: existing impersonation flow continues to work (session write + cache update
  on the same thread).

For Phase 2 (if pursued):

- Confirm `Utils.currentRequest` returns null inside a `runDetached` body.
- Confirm `runAsync` still has live request/response for async response rendering.

---

## Sequencing

| Phase | Ships as | When |
|---|---|---|
| 1 | Minor release | Now |
| 2 | Minor release | Optional. Only if Phase 1 leaves uncovered cases in practice. |
