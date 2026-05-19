/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.user

import groovy.transform.CompileStatic
import groovy.transform.Immutable

/**
 * Immutable snapshot of the identity associated with a thread of execution.
 *
 * Held in a {@link ThreadLocal} by {@link IdentityService} as the authoritative per-thread
 * identity source. Sourced from the HTTP session on the request thread, captured and
 * propagated across async boundaries (Grails {@code task {}}, {@code ClusterTask}) so that
 * code running on worker threads can resolve the originating user without touching the
 * (potentially recycled) servlet request.
 *
 * Either or both fields may be null if no user is associated with the thread.
 */
@CompileStatic
@Immutable
class HoistIdentity {
    /** Apparent user — the user the app appears to be running as (impersonated user, if any). */
    String username

    /** Authenticated user — always the actual logged-in user, regardless of impersonation. */
    String authUsername
}
