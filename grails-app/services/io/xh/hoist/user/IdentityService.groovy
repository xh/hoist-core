/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.user

import grails.async.Promises
import groovy.transform.CompileStatic
import io.xh.hoist.BaseService
import io.xh.hoist.config.ConfigService
import io.xh.hoist.security.BaseAuthenticationService
import io.xh.hoist.track.TrackService
import static io.xh.hoist.util.Utils.getCurrentRequest

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpSession

/**
 * Primary service for retrieving the logged-in HoistUser (aka the application user), with support
 * for impersonation. This service powers the getUser() and getUsername() methods in Hoist's
 * BaseService and BaseController classes.
 *
 * The session remains the durable source of truth for identity, but accessors read from a
 * per-thread {@link HoistIdentity} cache that is populated lazily on first access (request
 * thread) or installed explicitly at thread entry (async workers via
 * {@link IdentityPropagatingPromiseFactory}, {@link io.xh.hoist.cluster.ClusterTask}). The cache
 * is cleared at request end by {@link io.xh.hoist.HoistFilter} so request threads returned to
 * the Tomcat pool do not leak identity to the next request.
 *
 * Mutating operations ({@link #login}, {@link #logout}, {@link #impersonate},
 * {@link #endImpersonate}, {@link #noteUserAuthenticated}) update the session and the cache
 * together.
 *
 * This service is *not* intended for override or customization at the application level.
 */
@CompileStatic
class IdentityService extends BaseService {

    /** Per-thread identity cache — authoritative source for accessors after first population. */
    final ThreadLocal<HoistIdentity> threadIdentity = new ThreadLocal<HoistIdentity>()

    static public String AUTH_USER_KEY = 'xhAuthUser'
    static public String APPARENT_USER_KEY = 'xhApparentUser'

    BaseAuthenticationService authenticationService
    BaseUserService userService
    TrackService trackService
    ConfigService configService

    void init() {
        super.init()
        installIdentityPromisePropagation()
    }

    //------------------------------------
    // Implementation of IdentitySupport
    //-------------------------------------
    HoistUser getUser() {
        findHoistUser(false)
    }

    String getUsername() {
        getIdentity()?.username
    }

    HoistUser getAuthUser() {
        findHoistUser(true)
    }

    String getAuthUsername() {
        getIdentity()?.authUsername
    }

    /**
     * Is the authorized user currently impersonating someone else?
     */
    boolean isImpersonating() {
        def id = getIdentity()
        id && id.username != id.authUsername
    }

    /**
     * Begin an impersonation session.
     *
     * During an impersonation session, all calls to getUser() will return the impersonated user and appear
     * as if that user is the one browsing the app. Permissions, preferences, and other user-specific settings
     * will be loaded for the impersonated user to facilitate app testing and troubleshooting.
     *
     * Note that this operation will fail if the user does not have the HOIST_IMPERSONATE role as
     * well as authority to impersonate the target user. See
     * BaseUserService.impersonationTargetsForUser for more information.
     *
     * @param username - the user to impersonate.
     */
    HoistUser impersonate(String username) {
        checkImpersonationEnabled()
        def request = currentRequest
        if (!request) {
            throw new RuntimeException('Cannot impersonate when outside the context of a request')
        }

        def authUser = getAuthUser()
        if (!authUser?.canImpersonate) {
            throw new RuntimeException('User not found or user not authorized for impersonation.')
        }

        def targetUser = userService.find(username)
        if (!targetUser) {
            throw new RuntimeException("User '$username' not found")
        }

        if (!userService.impersonationTargetsForUser(authUser).contains(targetUser)) {
            throw new RuntimeException("'$authUser' is not authorized to impersonate '$username'")
        }

        // first explicitly end any existing impersonation session -- important for tracking.
        if (impersonating) endImpersonate()

        request.session[APPARENT_USER_KEY] = targetUser.username
        threadIdentity.set(new HoistIdentity(targetUser.username, authUser.username))

        trackImpersonate('Started impersonation', [target: targetUser.username])
        logInfo("User '$authUser.username' has started impersonating user '$targetUser.username'")

        return targetUser
    }

    /**
     * End the active impersonation session, if any, reverting to the current user browsing the app as themselves.
     */
    void endImpersonate() {
        def apparentUser = getUser(),
            authUser = getAuthUser()

        if (apparentUser != authUser) {
            trackImpersonate("Stopped impersonation", [target: apparentUser.username])
            logInfo("User '$authUser.username' has stopped impersonating user '$apparentUser.username'")
            currentRequest.session[APPARENT_USER_KEY] = authUser.username
            threadIdentity.set(new HoistIdentity(authUser.username, authUser.username))
        }
    }

    /**
     * Returns identify information about user, or null if there is no authenticated user.
     *
     * Used by client-side web app for identity management.
     */
    Map getClientConfig() {
        def authUser = getAuthUser()
        if (!authUser) return null

        def apparentUser = getUser()
        return (authUser != apparentUser) ?
            [
                apparentUser: apparentUser,
                apparentUserRoles: apparentUser.roles,
                authUser: authUser,
                authUserRoles: authUser.roles,
            ] :
            [
                user: authUser,
                roles: authUser.roles
            ]
    }

    /**
     * Process an interactive username + password based login.
     * Note SSO-based implementations of authenticationService will always return false.
     */
    boolean login(String username, String password) {
        authenticationService.login(currentRequest, username, password)
    }

    /**
     * Remove any login information for this user.
     * Note SSO-based implementations of authenticationService will always return false.
     */
    boolean logout() {
        if (authenticationService.logout()) {
            def session = getSessionIfExists()
            if (session) session[APPARENT_USER_KEY] = session[AUTH_USER_KEY] = null
            threadIdentity.remove()
            return true
        }

        return false
    }

    /**
     * Entry Point for AuthenticationService
     * Called by authenticationService when HoistUser has first been established for this session.
     */
    void noteUserAuthenticated(HttpServletRequest request, HoistUser user) {
        def session = request.session
        session[APPARENT_USER_KEY] = session[AUTH_USER_KEY] = user.username
        threadIdentity.set(new HoistIdentity(user.username, user.username))
    }

    /**
     * Entry Point for AuthenticationService
     * Called by authenticationService to determine if user has been set on this session
     */
    HoistUser findAuthUser(HttpServletRequest request) {
        HttpSession session = getSessionIfExists(request)
        String username = session ? session[AUTH_USER_KEY] : null
        username ? userService.find(username) : null
    }

    /**
     * Clear the per-thread identity cache. Called by {@link io.xh.hoist.HoistFilter} at the end
     * of every request to prevent identity from leaking between requests when Tomcat returns the
     * thread to its pool. Also called by async/cluster propagation helpers in their {@code finally}
     * blocks.
     */
    void clearThreadIdentity() {
        threadIdentity.remove()
    }

    //----------------------
    // Implementation
    //----------------------
    private void checkImpersonationEnabled() {
        if (!configService.getBool('xhEnableImpersonation')) {
            throw new RuntimeException('Impersonation is disabled for this app.')
        }
    }

    private void trackImpersonate(String msg, Map data) {
        trackService.track(
                category: 'Impersonate',
                severity: 'WARN',
                msg: msg,
                data: data
        )
    }

    private HoistUser findHoistUser(boolean authUser) {
        String name = authUser ? getAuthUsername() : getUsername()
        name ? userService.find(name) : null
    }

    /**
     * Resolve the identity for the current thread, populating from the live session on first
     * access if running on the request thread. Returns null when no identity is associated with
     * the thread and no live session is available (e.g. background timers, recycled-request
     * continuations that did not propagate identity).
     */
    private HoistIdentity getIdentity() {
        def id = threadIdentity.get()
        if (id != null) return id

        HttpSession session = getSessionIfExists()
        if (!session) return null

        id = new HoistIdentity(
            session[APPARENT_USER_KEY] as String,
            session[AUTH_USER_KEY] as String
        )
        threadIdentity.set(id)
        return id
    }

    private HttpSession getSessionIfExists(HttpServletRequest request = currentRequest) {
        // Do *not* create session for simple, early checks (avoid DOS attack).
        // Guard against IllegalStateException from a recycled RequestFacade — can occur when
        // identity is resolved on a thread whose RequestContextHolder still references a request
        // that has been recycled by Tomcat (e.g. async continuations).
        try {
            return request?.getSession(false)
        } catch (IllegalStateException ignored) {
            return null
        }
    }

    private void installIdentityPromisePropagation() {
        if (!(Promises.promiseFactory instanceof IdentityPropagatingPromiseFactory)) {
            Promises.promiseFactory = new IdentityPropagatingPromiseFactory(Promises.promiseFactory, this)
        }
    }
}
