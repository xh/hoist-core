/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2023 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.user

import groovy.transform.CompileStatic
import io.xh.hoist.BaseService
import io.xh.hoist.config.ConfigService
import io.xh.hoist.security.BaseAuthenticationService
import io.xh.hoist.track.TrackService
import static io.xh.hoist.util.Utils.getCurrentRequest

import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpSession

/**
 * Primary service for retrieving the logged-in HoistUser (aka the application user), with support
 * for impersonation. This service powers the getUser() and getUsername() methods in Hoist's
 * BaseService and BaseController classes.
 *
 * The implementation of this service uses the session to hold the authenticated username (and
 * potentially a distinct "apparent" username when impersonation is active). It depends on the app's
 * AuthenticationService to initialize the authenticated user via noteUserAuthenticated(), and
 * likewise delegates to the app's UserService to resolve usernames to actual HoistUser objects.
 *
 * This service is *not* intended for override or customization at the application level.
 */
@CompileStatic
class IdentityService extends BaseService {

    static public String AUTH_USER_KEY = 'xhAuthUser'
    static public String APPARENT_USER_KEY = 'xhApparentUser'

    BaseAuthenticationService authenticationService
    BaseUserService userService
    TrackService trackService
    ConfigService configService

    //------------------------------------
    // Implementation of IdentitySupport
    //-------------------------------------
    HoistUser getUser() {
        findApparentUser(currentRequest)
    }

    String getUsername() {
        HttpSession session = getSessionIfExists(currentRequest)
        session ? session[APPARENT_USER_KEY] : null
    }

    HoistUser getAuthUser() {
        findAuthUser(currentRequest)
    }

    String getAuthUsername() {
        HttpSession session = getSessionIfExists(currentRequest)
        session ? session[AUTH_USER_KEY] : null
    }


    /**
     * Is the authorized user currently impersonating someone else?
     */
    boolean isImpersonating() {
        def request = currentRequest,
            apparentUser = findApparentUser(request),
            authUser = findAuthUser(request)

        return apparentUser != authUser
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

        def authUser = findAuthUser(request)
        if (!authUser?.canImpersonate) {
            throw new RuntimeException('User not found or user not authorized for impersonation.')
        }

        def targetUser = userService.impersonationTargetsForUser(authUser).find {it.username == username}
        if (!targetUser) {
            throw new RuntimeException("'$authUser.username' does not have permissions to impersonate '$username'")
        }

        // first explicitly end any existing impersonation session -- important for tracking.
        if (impersonating) endImpersonate()

        request.session[APPARENT_USER_KEY] = targetUser.username

        trackImpersonate('Started impersonation', [target: targetUser.username])
        logInfo("User '$authUser.username' has started impersonating user '$targetUser.username'")

        return targetUser
    }

    /**
     * End the active impersonation session, if any, reverting to the current user browsing the app as themselves.
     */
    void endImpersonate() {
        def request = currentRequest,
            apparentUser = findApparentUser(request),
            authUser = findAuthUser(request)

        if (apparentUser != authUser) {
            trackImpersonate("Stopped impersonation", [target: apparentUser.username])
            logInfo("User '$authUser.username' has stopped impersonating user '$apparentUser.username'")
            request.session[APPARENT_USER_KEY] = authUser.username
        }
    }

    /**
     * Return minimal identify information for confirmed authenticated users.
     * Used by client-side web app for identity management.
     */
    Map getClientConfig() {
        def request = currentRequest,
            apparentUser = findApparentUser(request),
            authUser = findAuthUser(request)

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
            def session = getSessionIfExists(currentRequest)
            if (session) session[APPARENT_USER_KEY] = session[AUTH_USER_KEY] = null
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
    }

    HoistUser findApparentUser(HttpServletRequest request) {
        findHoistUserViaSessionKey(request, APPARENT_USER_KEY)
    }

    HoistUser findAuthUser(HttpServletRequest request) {
        findHoistUserViaSessionKey(request, AUTH_USER_KEY)
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
        );
    }

    private HoistUser findHoistUserViaSessionKey(HttpServletRequest request, String key) {
        HttpSession session = getSessionIfExists(request)
        String username = session ? session[key] : null
        username ? userService.find(username) : null
    }

    private HttpSession getSessionIfExists(HttpServletRequest request) {
        request?.getSession(false)  // Do *not* create session for simple, early checks (avoid DOS attack)
    }

}
