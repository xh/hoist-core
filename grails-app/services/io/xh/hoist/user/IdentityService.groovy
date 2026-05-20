/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.user

import groovy.transform.CompileStatic
import io.xh.hoist.BaseService
import io.xh.hoist.config.ConfigService
import io.xh.hoist.security.BaseAuthenticationService
import io.xh.hoist.track.TrackService
import static io.xh.hoist.util.Utils.getCurrentRequest

import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.socket.WebSocketSession

/**
 * Primary service for retrieving the logged-in HoistUser (aka the application user), with support
 * for impersonation. This service powers the getUser() and getUsername() methods in Hoist's
 * BaseService and BaseController classes.
 *
 * This service is *not* intended for override or customization at the application level.
 *
 * Implementation notes:
 * The session is the durable source of truth for identity, but accessors read from a per-thread
 * {@link HoistIdentity} cache installed explicitly at each thread entry point:
 * {@link io.xh.hoist.HoistFilter} (HTTP request), {@link io.xh.hoist.websocket.HoistWebSocketHandler}
 * (WS lifecycle callbacks), {@link io.xh.hoist.HoistPromiseFactory} (async {@code task} workers),
 * and {@link io.xh.hoist.cluster.ClusterTask} (cluster RPC). Mutating operations
 * ({@link #login}, {@link #logout}, {@link #impersonate}, {@link #endImpersonate},
 * {@link #noteUserAuthenticated}) update the session and the cache together.
 */
@CompileStatic
class IdentityService extends BaseService {

    final ThreadLocal<HoistIdentity> threadIdentity = new ThreadLocal<HoistIdentity>()

    private static final String IDENTITY_KEY = 'xhIdentity'

    BaseAuthenticationService authenticationService
    BaseUserService userService
    TrackService trackService
    ConfigService configService

    //------------------------------------
    // Implementation of IdentitySupport
    //-------------------------------------
    String getUsername() {
        threadIdentity.get()?.username
    }

    String getAuthUsername() {
        threadIdentity.get()?.authUsername
    }

    HoistUser getUser() {
        def name = username
        name ? userService.find(name) : null
    }

    HoistUser getAuthUser() {
        def name = authUsername
        name ? userService.find(name) : null
    }

    /**
     * Is the authorized user currently impersonating someone else?
     */
    boolean isImpersonating() {
        def identity = threadIdentity.get()
        identity && identity.username != identity.authUsername
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

        setIdentity(targetUser.username, authUser.username)

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
            setIdentity(authUser.username, authUser.username)
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
            clearIdentity()
            return true
        }

        return false
    }

    /**
     * Entry Point for AuthenticationService
     * Called by authenticationService when HoistUser has first been established for this session.
     */
    void noteUserAuthenticated(HttpServletRequest request, HoistUser user) {
        setIdentity(user.username, user.username, request)
    }


    //-----------------
    // Framework
    //----------------

    /**
     * Install the given identity on the current thread (or clear it, if null). Used by
     * {@link io.xh.hoist.HoistPromiseFactory} and {@link io.xh.hoist.cluster.ClusterTask}
     * to propagate identity captured/trampolined from an originating thread or node.
     *
     *  @internal - not for application use
     */
    void installThreadIdentity(HoistIdentity identity) {
        if (identity == null) {
            threadIdentity.remove()
        } else {
            threadIdentity.set(identity)
        }
    }

    /**
     * Install thread identity from the given request's existing session (does not create one).
     * Called by {@link io.xh.hoist.HoistFilter} at request entry.
     *
     * @internal - not for application use
     */
    void installIdentityFromRequest(HttpServletRequest request) {
        def session = request?.getSession(false)
        installThreadIdentity(session?.getAttribute(IDENTITY_KEY) as HoistIdentity)
    }

    /**
     * Install thread identity from a WebSocket session's handshake-captured attribute map.
     * Called by {@link io.xh.hoist.websocket.HoistWebSocketHandler} on each lifecycle callback.
     *
     * @internal - not for application use
     */
    void installIdentityFromWebSocketSession(WebSocketSession session) {
        installThreadIdentity(session?.attributes?.get(IDENTITY_KEY) as HoistIdentity)
    }

    //----------------------
    // Implementation
    //----------------------
    private void setIdentity(String username, String authUsername, HttpServletRequest request = currentRequest) {
        def identity = new HoistIdentity(username, authUsername)
        request.session[IDENTITY_KEY] = identity
        threadIdentity.set(identity)
    }

    private void clearIdentity() {
        def session = currentRequest?.getSession(false)
        if (session) session[IDENTITY_KEY] = null
        threadIdentity.remove()
    }

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


}
